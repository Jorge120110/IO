package com.example.georutassos.presentation.agent;

import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.example.georutassos.data.local.entity.EmergencyOrder;
import com.example.georutassos.data.repository.EmergencyRepository;
import com.example.georutassos.domain.model.GeoPoint;
import com.example.georutassos.domain.model.OrderStatus;
import com.example.georutassos.domain.routing.PolylineRouteTrimmer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ViewModel para el Modulo 3: Navegacion y Tracking en tiempo real (Rol AGENTE).
 * Gestiona la recepcion de ordenes, el calculo inicial con el algoritmo de Dijkstra,
 * el recorte dinamico de la Polyline conforme el agente avanza y el recalculo automatico
 * si se desvia del camino trazado.
 */
public class AgentNavigationViewModel extends ViewModel {

    public record NavigationUiState(
            @Nullable Long activeOrderId,
            long destinationNodeId,
            @Nullable GeoPoint currentAgentLocation,
            @NonNull List<GeoPoint> activePolylinePoints,
            boolean isCalculatingRoute,
            boolean wasRecalculatedDueToDeviation,
            double distanceToRouteMeters,
            @Nullable String statusMessage,
            @Nullable String errorMessage
    ) {
        public static NavigationUiState empty() {
            return new NavigationUiState(
                    null,
                    -1L,
                    null,
                    Collections.emptyList(),
                    false,
                    false,
                    0.0,
                    null,
                    null
            );
        }
    }

    private static final double ARRIVAL_THRESHOLD_METERS = 20.0;

    private final EmergencyRepository emergencyRepository;
    private final MutableLiveData<Long> currentAgentId = new MutableLiveData<>();
    private final LiveData<EmergencyOrder> activeOrderLiveData;
    private final MutableLiveData<NavigationUiState> navigationState =
            new MutableLiveData<>(NavigationUiState.empty());

    private double offRouteThresholdMeters = PolylineRouteTrimmer.DEFAULT_OFF_ROUTE_THRESHOLD_METERS;

    public AgentNavigationViewModel(@NonNull EmergencyRepository emergencyRepository) {
        this.emergencyRepository = emergencyRepository;
        this.activeOrderLiveData = Transformations.switchMap(
                currentAgentId,
                emergencyRepository::observeActiveOrderForAgent
        );
    }

    private static <T> void setOrPost(@NonNull MutableLiveData<T> liveData, @Nullable T value) {
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null && Looper.myLooper() == mainLooper) {
            liveData.setValue(value);
        } else {
            liveData.postValue(value);
        }
    }

    public void startListeningOrdersForAgent(long agentId) {
        setOrPost(currentAgentId, agentId);
    }

    public void clearActiveNavigation() {
        setOrPost(navigationState, NavigationUiState.empty());
    }

    public LiveData<EmergencyOrder> observeActiveOrder() {
        return activeOrderLiveData;
    }

    public LiveData<NavigationUiState> getNavigationState() {
        return navigationState;
    }

    public void setOffRouteThresholdMeters(double thresholdMeters) {
        this.offRouteThresholdMeters = thresholdMeters;
    }

    public void onEmergencyOrderReceived(@NonNull EmergencyOrder order) {
        NavigationUiState current = getCurrentStateOrEmpty();
        GeoPoint originPt = new GeoPoint(order.getOriginLat(), order.getOriginLng());
        GeoPoint destPt = new GeoPoint(order.getDestinationLat(), order.getDestinationLng());

        setOrPost(navigationState, new NavigationUiState(
                order.getId(),
                order.getDestinationNodeId(),
                originPt,
                current.activePolylinePoints(),
                true,
                false,
                0.0,
                "Calculando ruta optima con Dijkstra...",
                null
        ));

        emergencyRepository.updateOrderStatus(order.getId(), OrderStatus.IN_PROGRESS, null);

        emergencyRepository.calculateRouteForOrder(
                order,
                new EmergencyRepository.RepositoryCallback<>() {
                    @Override
                    public void onSuccess(List<GeoPoint> routePoints) {
                        List<GeoPoint> finalRoute = ensureOriginAndDestinationIncluded(routePoints, originPt, destPt);
                        setOrPost(navigationState, new NavigationUiState(
                                order.getId(),
                                order.getDestinationNodeId(),
                                originPt,
                                finalRoute,
                                false,
                                false,
                                0.0,
                                "Ruta trazada (" + finalRoute.size() + " puntos).",
                                null
                        ));
                    }

                    @Override
                    public void onError(Throwable error) {
                        List<GeoPoint> fallbackRoute = List.of(originPt, destPt);
                        setOrPost(navigationState, new NavigationUiState(
                                order.getId(),
                                order.getDestinationNodeId(),
                                originPt,
                                fallbackRoute,
                                false,
                                false,
                                0.0,
                                "Ruta directa trazada hacia el destino.",
                                null
                        ));
                    }
                }
        );
    }

    @NonNull
    private List<GeoPoint> ensureOriginAndDestinationIncluded(
            @Nullable List<GeoPoint> calculated,
            @NonNull GeoPoint originPt,
            @NonNull GeoPoint destPt
    ) {
        List<GeoPoint> raw = new ArrayList<>();
        if (calculated == null || calculated.isEmpty()) {
            raw.add(originPt);
            raw.add(destPt);
        } else {
            GeoPoint first = calculated.get(0);
            if (first.distanceToMeters(originPt) > 15.0) {
                raw.add(originPt);
            }
            raw.addAll(calculated);
            GeoPoint last = raw.get(raw.size() - 1);
            if (last.distanceToMeters(destPt) > 15.0) {
                raw.add(destPt);
            }
        }
        return densifyRouteSegmentsForSmoothSimulation(raw, 55.0);
    }

    @NonNull
    private List<GeoPoint> densifyRouteSegmentsForSmoothSimulation(
            @NonNull List<GeoPoint> points,
            double maxStepMeters
    ) {
        if (points.size() < 2) {
            return points;
        }
        List<GeoPoint> dense = new ArrayList<>(points.size() * 2);
        for (int i = 0; i < points.size() - 1; i++) {
            GeoPoint a = points.get(i);
            GeoPoint b = points.get(i + 1);
            dense.add(a);
            double dist = a.distanceToMeters(b);
            if (dist > maxStepMeters) {
                int steps = Math.min(25, (int) Math.ceil(dist / maxStepMeters));
                for (int s = 1; s < steps; s++) {
                    double t = s / (double) steps;
                    dense.add(new GeoPoint(
                            a.latitude() + (b.latitude() - a.latitude()) * t,
                            a.longitude() + (b.longitude() - a.longitude()) * t
                    ));
                }
            }
        }
        dense.add(points.get(points.size() - 1));
        return dense;
    }

    public void onRealTimeLocationUpdate(@NonNull GeoPoint newGpsLocation) {
        NavigationUiState state = getCurrentStateOrEmpty();
        if (state.isCalculatingRoute() || state.activePolylinePoints().isEmpty()) {
            return;
        }

        List<GeoPoint> currentPolyline = state.activePolylinePoints();
        GeoPoint destinationPoint = currentPolyline.get(currentPolyline.size() - 1);

        if (newGpsLocation.distanceToMeters(destinationPoint) <= ARRIVAL_THRESHOLD_METERS) {
            setOrPost(navigationState, new NavigationUiState(
                    state.activeOrderId(),
                    state.destinationNodeId(),
                    destinationPoint,
                    List.of(destinationPoint),
                    false,
                    false,
                    0.0,
                    "Unidad en el punto de la emergencia. Presione 'FINALIZAR MISION' para concluir.",
                    null
            ));
            return;
        }

        PolylineRouteTrimmer.PolylineUpdateResult trimResult = PolylineRouteTrimmer.evaluateAndTrim(
                newGpsLocation,
                currentPolyline,
                offRouteThresholdMeters
        );

        if (!trimResult.isOffRoute()) {
            setOrPost(navigationState, new NavigationUiState(
                    state.activeOrderId(),
                    state.destinationNodeId(),
                    newGpsLocation,
                    trimResult.trimmedPolyline(),
                    false,
                    false,
                    trimResult.distanceToRouteMeters(),
                    "En ruta (desviacion: " + Math.round(trimResult.distanceToRouteMeters()) + " m).",
                    null
            ));
        } else {
            triggerRouteRecalculation(state, newGpsLocation, trimResult.distanceToRouteMeters());
        }
    }

    private void triggerRouteRecalculation(
            @NonNull NavigationUiState state,
            @NonNull GeoPoint currentGpsLocation,
            double deviationMeters
    ) {
        long orderId = state.activeOrderId() != null ? state.activeOrderId() : -1L;
        long destinationNodeId = state.destinationNodeId();

        setOrPost(navigationState, new NavigationUiState(
                state.activeOrderId(),
                destinationNodeId,
                currentGpsLocation,
                state.activePolylinePoints(),
                true,
                true,
                deviationMeters,
                "Desvio detectado (" + Math.round(deviationMeters) + " m). Recalculando con Dijkstra...",
                null
        ));

        emergencyRepository.recalculateRouteFromCurrentLocation(
                orderId,
                currentGpsLocation,
                destinationNodeId,
                new EmergencyRepository.RepositoryCallback<>() {
                    @Override
                    public void onSuccess(List<GeoPoint> newRoutePoints) {
                        setOrPost(navigationState, new NavigationUiState(
                                state.activeOrderId(),
                                destinationNodeId,
                                currentGpsLocation,
                                newRoutePoints,
                                false,
                                true,
                                0.0,
                                "Ruta recalculada con Dijkstra desde la ubicacion actual.",
                                null
                        ));
                    }

                    @Override
                    public void onError(Throwable error) {
                        setOrPost(navigationState, new NavigationUiState(
                                state.activeOrderId(),
                                destinationNodeId,
                                currentGpsLocation,
                                state.activePolylinePoints(),
                                false,
                                true,
                                deviationMeters,
                                null,
                                "Error al recalcular ruta: " + error.getMessage()
                        ));
                    }
                }
        );
    }

    private NavigationUiState getCurrentStateOrEmpty() {
        NavigationUiState value = navigationState.getValue();
        return value != null ? value : NavigationUiState.empty();
    }
}
