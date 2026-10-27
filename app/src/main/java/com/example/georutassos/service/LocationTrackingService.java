package com.example.georutassos.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.georutassos.data.local.database.GeoRutaDatabase;
import com.example.georutassos.data.repository.EmergencyRepository;
import com.example.georutassos.domain.model.GeoPoint;
import com.example.georutassos.domain.notification.NotificationSimulator;
import com.example.georutassos.domain.routing.DijkstraRouteEngine;
import com.example.georutassos.domain.routing.PolylineRouteTrimmer;
import com.example.georutassos.domain.routing.RoomDijkstraEngineWrapper;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Foreground Service de Tracking GPS y Ruteo Dinámico para el rol AGENTE.
 * <ul>
 *     <li>Mantiene la captura de ubicación GPS en primer plano (Foreground Service).</li>
 *     <li>Expone {@link #trimOrRecalculatePolyline(GeoPoint)} para recortar dinámicamente la
 *     Polyline de Google Maps conforme el agente avanza o recalcular con Dijkstra si se desvía.</li>
 *     <li>Incluye un simulador de telemetría GPS para pruebas de avance y desvío sin necesidad
 *     de caminar físicamente.</li>
 * </ul>
 */
public class LocationTrackingService extends Service implements LocationListener {

    public static final String ACTION_START_TRACKING = "com.example.georutassos.action.START_TRACKING";
    public static final String ACTION_STOP_TRACKING = "com.example.georutassos.action.STOP_TRACKING";

    public static final String EXTRA_ORDER_ID = "extra_order_id";
    public static final String EXTRA_ORIGIN_NODE_ID = "extra_origin_node_id";
    public static final String EXTRA_DESTINATION_NODE_ID = "extra_destination_node_id";

    private static final String CHANNEL_ID = "georuta_sos_tracking_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long MIN_TIME_MS = 2_000L;
    private static final float MIN_DISTANCE_METERS = 3.0f;

    /**
     * Evento publicado en tiempo real hacia los observadores (ViewModel / Mapa).
     */
    public record RouteTrackingUpdate(
            @NonNull GeoPoint currentAgentLocation,
            @NonNull List<GeoPoint> updatedPolyline,
            boolean recalculatedWithDijkstra,
            double deviationMeters
    ) {}

    public class LocalBinder extends Binder {
        public LocationTrackingService getService() {
            return LocationTrackingService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final MutableLiveData<RouteTrackingUpdate> routeTrackingLiveData = new MutableLiveData<>();

    private LocationManager locationManager;
    private EmergencyRepository emergencyRepository;
    private DijkstraRouteEngine dijkstraEngine;

    private ScheduledExecutorService simulationScheduler;
    private ScheduledFuture<?> activeSimulationTask;

    private long activeOrderId = -1L;
    private long destinationNodeId = -1L;
    private volatile List<GeoPoint> currentPolyline = Collections.emptyList();
    private double offRouteThresholdMeters = PolylineRouteTrimmer.DEFAULT_OFF_ROUTE_THRESHOLD_METERS;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        GeoRutaDatabase database = GeoRutaDatabase.getInstance(getApplicationContext());
        dijkstraEngine = new RoomDijkstraEngineWrapper(database.graphDao());
        emergencyRepository = new EmergencyRepository(
                database.emergencyOrderDao(),
                database.userDao(),
                dijkstraEngine,
                new NotificationSimulator()
        );
        simulationScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Permite inyectar una implementación personalizada de {@link DijkstraRouteEngine} en tiempo
     * de ejecución.
     */
    public void setDijkstraRouteEngine(@NonNull DijkstraRouteEngine customEngine) {
        this.dijkstraEngine = customEngine;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP_TRACKING.equals(action)) {
                stopTrackingAndService();
                return START_NOT_STICKY;
            } else if (ACTION_START_TRACKING.equals(action)) {
                long orderId = intent.getLongExtra(EXTRA_ORDER_ID, -1L);
                long originNodeId = intent.getLongExtra(EXTRA_ORIGIN_NODE_ID, -1L);
                long destNodeId = intent.getLongExtra(EXTRA_DESTINATION_NODE_ID, -1L);

                try {
                    Notification notification = buildForegroundNotification("Rastreando ruta de emergencia...");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                                NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        );
                    } else {
                        startForeground(NOTIFICATION_ID, notification);
                    }
                } catch (Throwable ignored) {
                    // Evita excepciones en Android 14+ si el servicio se invoca antes de conceder permiso GPS
                }
                initializeOrderRoute(orderId, originNodeId, destNodeId);
                startHardwareLocationUpdates();
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * LiveData observable desde {@code AgentNavigationViewModel} o controladores para actualizar
     * la Polyline de Google Maps en tiempo real.
     */
    public LiveData<RouteTrackingUpdate> observeRouteTrackingUpdates() {
        return routeTrackingLiveData;
    }

    /**
     * Calcula la ruta inicial de la Orden de Emergencia usando Dijkstra y prepara la Polyline.
     */
    public void initializeOrderRoute(long orderId, long originNodeId, long destNodeId) {
        this.activeOrderId = orderId;
        this.destinationNodeId = destNodeId;

        if (originNodeId < 0 || destNodeId < 0) {
            return;
        }

        emergencyRepository.calculateRouteBetweenNodes(
                originNodeId,
                destNodeId,
                new EmergencyRepository.RepositoryCallback<>() {
                    @Override
                    public void onSuccess(List<GeoPoint> routePoints) {
                        currentPolyline = routePoints;
                        if (!routePoints.isEmpty()) {
                            routeTrackingLiveData.postValue(new RouteTrackingUpdate(
                                    routePoints.get(0),
                                    routePoints,
                                    false,
                                    0.0
                            ));
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        // Error de cálculo inicial manejado silenciosamente en background
                    }
                }
        );
    }

    /**
     * Método principal requerido para actualizar la posición del agente:
     * Recorta la Polyline conforme el agente avanza por la ruta, o recalcula el grafo con Dijkstra
     * si el agente se desvía más allá del umbral permitido.
     */
    public void trimOrRecalculatePolyline(@NonNull GeoPoint currentGpsLocation) {
        List<GeoPoint> snapshotPolyline = this.currentPolyline;
        if (snapshotPolyline == null || snapshotPolyline.isEmpty()) {
            return;
        }

        PolylineRouteTrimmer.PolylineUpdateResult result = PolylineRouteTrimmer.evaluateAndTrim(
                currentGpsLocation,
                snapshotPolyline,
                offRouteThresholdMeters
        );

        if (!result.isOffRoute()) {
            this.currentPolyline = result.trimmedPolyline();
            routeTrackingLiveData.postValue(new RouteTrackingUpdate(
                    currentGpsLocation,
                    result.trimmedPolyline(),
                    false,
                    result.distanceToRouteMeters()
            ));
        } else {
            // El agente se desvió del camino trazado: recalcular con Dijkstra desde el nodo más cercano
            emergencyRepository.recalculateRouteFromCurrentLocation(
                    activeOrderId,
                    currentGpsLocation,
                    destinationNodeId,
                    new EmergencyRepository.RepositoryCallback<>() {
                        @Override
                        public void onSuccess(List<GeoPoint> recalculatedRoute) {
                            currentPolyline = recalculatedRoute;
                            routeTrackingLiveData.postValue(new RouteTrackingUpdate(
                                    currentGpsLocation,
                                    recalculatedRoute,
                                    true,
                                    result.distanceToRouteMeters()
                            ));
                        }

                        @Override
                        public void onError(Throwable error) {
                            // Mantener la última Polyline válida en caso de fallo
                        }
                    }
            );
        }
    }

    /**
     * Simula la recepción periódica de coordenadas GPS en tiempo real (útil para emular el avance
     * o desvío de la patrulla/ambulancia sobre una lista de puntos de prueba).
     */
    public void startSimulatedGpsStream(@NonNull List<GeoPoint> simulatedGpsStream, long intervalMs) {
        stopSimulatedGpsStream();
        if (simulatedGpsStream.isEmpty()) {
            return;
        }

        final int[] index = {0};
        activeSimulationTask = simulationScheduler.scheduleAtFixedRate(() -> {
            if (index[0] < simulatedGpsStream.size()) {
                GeoPoint simulatedPoint = simulatedGpsStream.get(index[0]++);
                trimOrRecalculatePolyline(simulatedPoint);
            } else {
                stopSimulatedGpsStream();
            }
        }, 0L, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stopSimulatedGpsStream() {
        if (activeSimulationTask != null && !activeSimulationTask.isCancelled()) {
            activeSimulationTask.cancel(true);
        }
    }

    @SuppressLint("MissingPermission")
    private void startHardwareLocationUpdates() {
        if (locationManager == null) {
            return;
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        MIN_TIME_MS,
                        MIN_DISTANCE_METERS,
                        this,
                        Looper.getMainLooper()
                );
            }
        } catch (SecurityException ignored) {
            // Los permisos ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION deben ser concedidos antes de iniciar el servicio
        }
    }

    private void stopHardwareLocationUpdates() {
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
        trimOrRecalculatePolyline(point);
    }

    private void stopTrackingAndService() {
        stopSimulatedGpsStream();
        stopHardwareLocationUpdates();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopSimulatedGpsStream();
        stopHardwareLocationUpdates();
        if (simulationScheduler != null) {
            simulationScheduler.shutdownNow();
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GeoRuta SOS - Tracking de Agentes",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Canal para el monitoreo GPS en tiempo real durante emergencias.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GeoRuta SOS Activo")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
