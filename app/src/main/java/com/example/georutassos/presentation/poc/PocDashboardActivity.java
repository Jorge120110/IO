package com.example.georutassos.presentation.poc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.georutassos.R;
import com.example.georutassos.data.local.database.DemoSeedDataInitializer;
import com.example.georutassos.data.local.database.GeoRutaDatabase;
import com.example.georutassos.data.local.entity.EmergencyOrder;
import com.example.georutassos.data.local.entity.User;
import com.example.georutassos.data.repository.EmergencyRepository;
import com.example.georutassos.data.repository.UserRepository;
import com.example.georutassos.data.sync.RealtimeCrossDeviceSyncManager;
import com.example.georutassos.domain.model.ApprovalStatus;
import com.example.georutassos.domain.model.EmergencyServiceType;
import com.example.georutassos.domain.model.GeoPoint;
import com.example.georutassos.domain.model.OrderStatus;
import com.example.georutassos.domain.model.PresetStationCatalog;
import com.example.georutassos.domain.model.UserRole;
import com.example.georutassos.domain.notification.NotificationSimulator;
import com.example.georutassos.domain.routing.DijkstraRouteEngine;
import com.example.georutassos.domain.routing.RoomDijkstraEngineWrapper;
import com.example.georutassos.presentation.GeoRutaViewModelFactory;
import com.example.georutassos.presentation.admin.AdminMonitorViewModel;
import com.example.georutassos.presentation.agent.AgentNavigationViewModel;
import com.example.georutassos.presentation.registration.RegistrationViewModel;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Actividad Principal de GeoRuta SOS con Sincronizacion en Tiempo Real entre Telefonos:
 * 1) Creacion de usuarios con auto-aprobacion, inicio de sesion inmediato y sincronizacion automatica.
 * 2) Estaciones dinamicas generadas directamente alrededor de la ubicacion GPS exacta del usuario.
 * 3) Reconocimiento explicito de Agentes por servicio (Policia, Hospital, Bomberos) y Administrador.
 * 4) Sincronizacion continua entre multiples telefonos (Stream Nube HTTPS + Wi-Fi P2P).
 */
public class PocDashboardActivity extends ComponentActivity {

    private static final GeoPoint FALLBACK_INITIAL_LOCATION = new GeoPoint(14.6277121, -90.5602439);

    private GeoRutaDatabase database;
    private DijkstraRouteEngine dijkstraEngine;
    private UserRepository userRepository;
    private EmergencyRepository emergencyRepository;
    private RealtimeCrossDeviceSyncManager syncManager;
    private RegistrationViewModel registrationViewModel;
    private AdminMonitorViewModel adminMonitorViewModel;
    private AgentNavigationViewModel agentNavigationViewModel;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback continuousLocationCallback;

    // Cabecera global
    private TextView txtActiveIdentityBanner;
    private TextView txtSyncStatusBanner;
    private TextView txtUserGpsStatusBanner;
    private TextView txtLastNotificationBanner;
    private Button btnForceSyncNow;
    private Button btnRequestLocationPermission;
    private Button btnLogoutSession;

    // Contenedores raiz de las 3 pantallas
    private LinearLayout layoutAuthScreen;
    private LinearLayout layoutAdminWorkspace;
    private LinearLayout layoutAgentWorkspace;

    // Pantalla 1: Login y Registro Auto-Aprobado
    private LinearLayout cardLoginContainer;
    private LinearLayout cardRegisterContainer;
    private Button btnShowLoginTab;
    private Button btnShowRegisterTab;
    private EditText inputLoginDpi;
    private EditText inputLoginPassword;
    private TextView txtLoginStatusFeedback;
    private TextView txtRegisteredUsersListSummary;
    private Spinner spinnerActiveSessionUser;
    private EditText inputPeerDeviceIp;

    private Spinner spinnerRegRole;
    private EditText inputRegFullName;
    private EditText inputRegDpi;
    private EditText inputRegPassword;
    private EditText inputRegPhones;
    private LinearLayout containerAgentRegistrationFields;
    private Spinner spinnerRegServiceType;
    private EditText inputRegNip;
    private TextView txtApplicantCurrentLocation;
    private Spinner spinnerNearbyStations;
    private EditText inputRegAddress;
    private TextView txtSelectedStationCoords;
    private RouteMapCanvasView mapRegistrationStation;
    private TextView txtRegistrationStatus;

    // Pantalla 2: Administrador (Despacho y Busqueda por DPI)
    private LinearLayout cardAdminDispatch;
    private LinearLayout cardAdminSearchDpi;
    private Button btnAdminTabDispatch;
    private Button btnAdminTabSearchDpi;
    private TextView txtAdminOnlineUnitsSummary;
    private CheckBox chkServicePolicia;
    private CheckBox chkServiceHospital;
    private CheckBox chkServiceBomberos;
    private EditText inputOrderDescription;
    private TextView txtDispatchMapInstruction;
    private RouteMapCanvasView mapAdminDispatchDestination;
    
    
    
    
    
    
    
    
    
    
    
    
    private TextView txtDispatchFeedback;

    private Spinner spinnerAdminQuickPickAgentDpi;
    private EditText inputSearchAgentDpi;
    private TextView txtSearchDpiStatus;
    private TextView txtSearchedAgentProfile;
    private TextView txtAuditHistoryList;

    // Pantalla 3: Agente (Espera sin sucesos, Mi Perfil, Emergencia Activa con Mapa)
    private TextView txtAgentRecognizedServiceBadge;
    private Button btnToggleAgentProfile;
    private LinearLayout cardAgentProfile;
    private TextView txtAgentOwnProfileDetails;
    private LinearLayout cardAgentNoEvents;
    private TextView txtAgentStandbySubtitle;
    private TextView txtAgentRealtimeCountdown;
    private LinearLayout cardAgentActiveEmergency;
    private TextView txtActiveEmergencyOrderBanner;
    private RouteMapCanvasView routeMapCanvasView;
    private TextView txtNavigationTelemetry;

    // Estado en memoria
    private final List<User> allUsersCache = new ArrayList<>();
    private final List<User> agentUsersCache = new ArrayList<>();
    @Nullable
    private User loggedInUser = null;
    @Nullable
    private EmergencyOrder currentActiveAgentOrder = null;

    // Ubicacion actual precisa del dispositivo/solicitante
    @NonNull
    private GeoPoint currentUserPreciseLocation = FALLBACK_INITIAL_LOCATION;
    @Nullable
    private String currentUserAreaName = null;
    private boolean hasFineLocationPermission = false;
    private boolean hasCenteredAdminDestinationOnRealGps = false;

    // Seleccion dinamica de estacion en registro
    private long selectedStationNodeId = User.DEFAULT_STATION_NODE_ID;
    private GeoPoint selectedStationGeoPoint = FALLBACK_INITIAL_LOCATION;
    private List<PresetStationCatalog.StationOption> currentDynamicStationOptions = new ArrayList<>();
    private int selectedStationSpinnerIndex = 0;

    // Punto de llegada en el despacho del Administrador
    private GeoPoint selectedEmergencyDestinationPoint = new GeoPoint(14.6195031, -90.5153018);

    @Nullable
    private CountDownTimer realtimeTestCountDownTimer;
    private final android.os.Handler autoWalkHandler = new android.os.Handler(Looper.getMainLooper());
    private boolean isAutoWalkingRoute = false;
    private final Runnable autoWalkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAutoWalkingRoute) {
                return;
            }
            AgentNavigationViewModel.NavigationUiState state =
                    agentNavigationViewModel.getNavigationState().getValue();
            if (state == null || state.activePolylinePoints().isEmpty()) {
                stopAutoWalkRouteSimulation();
                return;
            }
            List<GeoPoint> poly = state.activePolylinePoints();
            if (poly.size() <= 1) {
                agentNavigationViewModel.onRealTimeLocationUpdate(poly.get(0));
                stopAutoWalkRouteSimulation();
                Toast.makeText(
                        PocDashboardActivity.this,
                        "La unidad llego al punto de la emergencia. Presione 'FINALIZAR MISION' para concluir.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }
            GeoPoint nextPoint = poly.get(1);
            agentNavigationViewModel.onRealTimeLocationUpdate(nextPoint);
            autoWalkHandler.postDelayed(this, 450L);
        }
    };

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean fineGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                Boolean coarseGranted = result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);
                hasFineLocationPermission = Boolean.TRUE.equals(fineGranted) || Boolean.TRUE.equals(coarseGranted);
                if (hasFineLocationPermission) {
                    fetchCurrentDeviceLocationAndCenterMaps();
                    startContinuousGpsUpdates();
                } else {
                    txtUserGpsStatusBanner.setText(
                            "Permiso de ubicacion denegado. Active el permiso de ubicacion precisa para calcular estaciones en su posicion real."
                    );
                    applyUserLocationToAllMaps(currentUserPreciseLocation, false);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_poc_dashboard);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        initContinuousLocationCallback();
        initArchitectureDependencies();
        bindViews();
        configureAuthAndRegistrationControls();
        configureAdminControls();
        configureAgentControls();
        observeViewModels();

        syncManager.startContinuousSync();
        requestExplicitPreciseLocationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapRegistrationStation != null) {
            mapRegistrationStation.onHostResume();
        }
        if (mapAdminDispatchDestination != null) {
            mapAdminDispatchDestination.onHostResume();
        }
        if (routeMapCanvasView != null) {
            routeMapCanvasView.onHostResume();
        }
        if (hasFineLocationPermission) {
            startContinuousGpsUpdates();
        }
        if (syncManager != null) {
            syncManager.syncNowAsync(false);
        }
    }

    @Override
    protected void onPause() {
        stopContinuousGpsUpdates();
        if (mapRegistrationStation != null) {
            mapRegistrationStation.onHostPause();
        }
        if (mapAdminDispatchDestination != null) {
            mapAdminDispatchDestination.onHostPause();
        }
        if (routeMapCanvasView != null) {
            routeMapCanvasView.onHostPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopContinuousGpsUpdates();
        stopAutoWalkRouteSimulation();
        if (realtimeTestCountDownTimer != null) {
            realtimeTestCountDownTimer.cancel();
        }
        if (mapRegistrationStation != null) {
            mapRegistrationStation.onHostDestroy();
        }
        if (mapAdminDispatchDestination != null) {
            mapAdminDispatchDestination.onHostDestroy();
        }
        if (routeMapCanvasView != null) {
            routeMapCanvasView.onHostDestroy();
        }
        super.onDestroy();
    }

    private void initContinuousLocationCallback() {
        continuousLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location latest = locationResult.getLastLocation();
                if (latest != null) {
                    onRealDeviceGpsLocationObtained(latest);
                }
            }
        };
    }

    private void initArchitectureDependencies() {
        database = GeoRutaDatabase.getInstance(this);
        DemoSeedDataInitializer.seedIfEmpty(database);

        NotificationSimulator notificationSimulator = new NotificationSimulator();
        notificationSimulator.setNotificationListener((title, message, targetIdentifier) -> runOnUiThread(() -> {
            if (txtLastNotificationBanner != null) {
                txtLastNotificationBanner.setText(
                        "Notificacion [" + targetIdentifier + "] " + title + ": " + message
                );
            }
        }));
        dijkstraEngine = new RoomDijkstraEngineWrapper(database.graphDao());

        userRepository = new UserRepository(database.userDao(), notificationSimulator);
        emergencyRepository = new EmergencyRepository(
                database.emergencyOrderDao(),
                database.userDao(),
                dijkstraEngine,
                notificationSimulator
        );

        syncManager = RealtimeCrossDeviceSyncManager.getInstance(database, notificationSimulator);
        userRepository.setSyncManager(syncManager);
        emergencyRepository.setSyncManager(syncManager);

        syncManager.addSyncListener((usersChanged, ordersChanged) -> {
            if (usersChanged) {
                userRepository.refreshUserListsAsync();
            }
            if (ordersChanged) {
                emergencyRepository.refreshAllCachedAgentsAsync();
                if (loggedInUser != null && loggedInUser.getRole() == UserRole.AGENTE) {
                    emergencyRepository.refreshAgentOrdersAsync(loggedInUser.getId());
                }
            }
        });

        GeoRutaViewModelFactory factory = new GeoRutaViewModelFactory(userRepository, emergencyRepository);
        ViewModelProvider provider = new ViewModelProvider(this, factory);

        registrationViewModel = provider.get(RegistrationViewModel.class);
        adminMonitorViewModel = provider.get(AdminMonitorViewModel.class);
        agentNavigationViewModel = provider.get(AgentNavigationViewModel.class);
    }

    private void bindViews() {
        txtActiveIdentityBanner = findViewById(R.id.txtActiveIdentityBanner);
        txtSyncStatusBanner = findViewById(R.id.txtSyncStatusBanner);
        txtUserGpsStatusBanner = findViewById(R.id.txtUserGpsStatusBanner);
        txtLastNotificationBanner = findViewById(R.id.txtLastNotificationBanner);
        btnForceSyncNow = findViewById(R.id.btnForceSyncNow);
        btnRequestLocationPermission = findViewById(R.id.btnRequestLocationPermission);
        btnLogoutSession = findViewById(R.id.btnLogoutSession);

        layoutAuthScreen = findViewById(R.id.layoutAuthScreen);
        layoutAdminWorkspace = findViewById(R.id.layoutAdminWorkspace);
        layoutAgentWorkspace = findViewById(R.id.layoutAgentWorkspace);

        cardLoginContainer = findViewById(R.id.cardLoginContainer);
        cardRegisterContainer = findViewById(R.id.cardRegisterContainer);
        btnShowLoginTab = findViewById(R.id.btnShowLoginTab);
        btnShowRegisterTab = findViewById(R.id.btnShowRegisterTab);
        inputLoginDpi = findViewById(R.id.inputLoginDpi);
        inputLoginPassword = findViewById(R.id.inputLoginPassword);
        txtLoginStatusFeedback = findViewById(R.id.txtLoginStatusFeedback);
        txtRegisteredUsersListSummary = findViewById(R.id.txtRegisteredUsersListSummary);
        spinnerActiveSessionUser = findViewById(R.id.spinnerActiveSessionUser);
        inputPeerDeviceIp = findViewById(R.id.inputPeerDeviceIp);

        spinnerRegRole = findViewById(R.id.spinnerRegRole);
        inputRegFullName = findViewById(R.id.inputRegFullName);
        inputRegDpi = findViewById(R.id.inputRegDpi);
        inputRegPassword = findViewById(R.id.inputRegPassword);
        inputRegPhones = findViewById(R.id.inputRegPhones);
        containerAgentRegistrationFields = findViewById(R.id.containerAgentRegistrationFields);
        spinnerRegServiceType = findViewById(R.id.spinnerRegServiceType);
        inputRegNip = findViewById(R.id.inputRegNip);
        txtApplicantCurrentLocation = findViewById(R.id.txtApplicantCurrentLocation);
        spinnerNearbyStations = findViewById(R.id.spinnerNearbyStations);
        inputRegAddress = findViewById(R.id.inputRegAddress);
        txtSelectedStationCoords = findViewById(R.id.txtSelectedStationCoords);
        mapRegistrationStation = findViewById(R.id.mapRegistrationStation);
        txtRegistrationStatus = findViewById(R.id.txtRegistrationStatus);

        cardAdminDispatch = findViewById(R.id.cardAdminDispatch);
        cardAdminSearchDpi = findViewById(R.id.cardAdminSearchDpi);
        btnAdminTabDispatch = findViewById(R.id.btnAdminTabDispatch);
        btnAdminTabSearchDpi = findViewById(R.id.btnAdminTabSearchDpi);
        txtAdminOnlineUnitsSummary = findViewById(R.id.txtAdminOnlineUnitsSummary);
        chkServicePolicia = findViewById(R.id.chkServicePolicia);
        chkServiceHospital = findViewById(R.id.chkServiceHospital);
        chkServiceBomberos = findViewById(R.id.chkServiceBomberos);
        inputOrderDescription = findViewById(R.id.inputOrderDescription);
        txtDispatchMapInstruction = findViewById(R.id.txtDispatchMapInstruction);
        mapAdminDispatchDestination = findViewById(R.id.mapAdminDispatchDestination);
        txtDispatchFeedback = findViewById(R.id.txtDispatchFeedback);

        spinnerAdminQuickPickAgentDpi = findViewById(R.id.spinnerAdminQuickPickAgentDpi);
        inputSearchAgentDpi = findViewById(R.id.inputSearchAgentDpi);
        txtSearchDpiStatus = findViewById(R.id.txtSearchDpiStatus);
        txtSearchedAgentProfile = findViewById(R.id.txtSearchedAgentProfile);
        txtAuditHistoryList = findViewById(R.id.txtAuditHistoryList);

        txtAgentRecognizedServiceBadge = findViewById(R.id.txtAgentRecognizedServiceBadge);
        btnToggleAgentProfile = findViewById(R.id.btnToggleAgentProfile);
        cardAgentProfile = findViewById(R.id.cardAgentProfile);
        txtAgentOwnProfileDetails = findViewById(R.id.txtAgentOwnProfileDetails);
        cardAgentNoEvents = findViewById(R.id.cardAgentNoEvents);
        txtAgentStandbySubtitle = findViewById(R.id.txtAgentStandbySubtitle);
        txtAgentRealtimeCountdown = findViewById(R.id.txtAgentRealtimeCountdown);
        cardAgentActiveEmergency = findViewById(R.id.cardAgentActiveEmergency);
        txtActiveEmergencyOrderBanner = findViewById(R.id.txtActiveEmergencyOrderBanner);
        routeMapCanvasView = findViewById(R.id.routeMapCanvasView);
        txtNavigationTelemetry = findViewById(R.id.txtNavigationTelemetry);

        btnForceSyncNow.setOnClickListener(v -> {
            syncManager.syncNowAsync(true);
            Toast.makeText(this, "Sincronizando usuarios y ordenes en tiempo real...", Toast.LENGTH_SHORT).show();
        });
        btnRequestLocationPermission.setOnClickListener(v -> {
            hasCenteredAdminDestinationOnRealGps = false;
            requestExplicitPreciseLocationPermission();
        });
        btnLogoutSession.setOnClickListener(v -> {
            if (realtimeTestCountDownTimer != null) {
                realtimeTestCountDownTimer.cancel();
            }
            registrationViewModel.logout();
        });
    }

    private void requestExplicitPreciseLocationPermission() {
        boolean fineGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        if (fineGranted || coarseGranted) {
            hasFineLocationPermission = true;
            fetchCurrentDeviceLocationAndCenterMaps();
            startContinuousGpsUpdates();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    @SuppressLint("MissingPermission")
    private void startContinuousGpsUpdates() {
        if (!hasFineLocationPermission || fusedLocationClient == null || continuousLocationCallback == null) {
            return;
        }
        try {
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
                    .setMinUpdateIntervalMillis(2000L)
                    .setMinUpdateDistanceMeters(8.0f)
                    .build();
            fusedLocationClient.requestLocationUpdates(request, continuousLocationCallback, Looper.getMainLooper());
        } catch (Throwable ignored) {
        }
    }

    private void stopContinuousGpsUpdates() {
        if (fusedLocationClient != null && continuousLocationCallback != null) {
            try {
                fusedLocationClient.removeLocationUpdates(continuousLocationCallback);
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void fetchCurrentDeviceLocationAndCenterMaps() {
        if (!hasFineLocationPermission) {
            return;
        }

        // 1. Lectura inmediata de LocationManager del sistema para no esperar al primer fix
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                Location bestLast = null;
                for (String provider : new String[]{
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER
                }) {
                    try {
                        Location loc = lm.getLastKnownLocation(provider);
                        if (loc != null && (bestLast == null || loc.getTime() > bestLast.getTime())) {
                            bestLast = loc;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (bestLast != null) {
                    onRealDeviceGpsLocationObtained(bestLast);
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. Ultima ubicacion conocida en FusedLocationProviderClient
        try {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(this, loc -> {
                        if (loc != null) {
                            onRealDeviceGpsLocationObtained(loc);
                        }
                    });
        } catch (Throwable ignored) {
        }

        // 3. Ubicacion fresca de alta precision GPS
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this, location -> {
                        if (location != null) {
                            onRealDeviceGpsLocationObtained(location);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private void onRealDeviceGpsLocationObtained(@NonNull Location location) {
        GeoPoint newExactPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        double movedMeters = currentUserPreciseLocation.distanceToMeters(newExactPoint);
        currentUserPreciseLocation = newExactPoint;

        String areaText = (currentUserAreaName != null && !currentUserAreaName.isBlank())
                ? (" (" + currentUserAreaName + ")")
                : "";
        txtUserGpsStatusBanner.setText(String.format(
                Locale.US,
                "Ubicacion Exacta GPS Activa%s: (%.5f, %.5f)",
                areaText,
                newExactPoint.latitude(),
                newExactPoint.longitude()
        ));

        boolean shouldRecenterDestination = !hasCenteredAdminDestinationOnRealGps;
        hasCenteredAdminDestinationOnRealGps = true;

        if (movedMeters > 5.0 || shouldRecenterDestination || currentDynamicStationOptions.isEmpty()) {
            resolveAreaNameWithGeocoderAsync(newExactPoint);
            applyUserLocationToAllMaps(newExactPoint, shouldRecenterDestination);
        }
    }

    private void resolveAreaNameWithGeocoderAsync(@NonNull GeoPoint point) {
        GeoRutaDatabase.databaseWriteExecutor.execute(() -> {
            try {
                if (!Geocoder.isPresent()) {
                    return;
                }
                Geocoder geocoder = new Geocoder(PocDashboardActivity.this, Locale.getDefault());
                @SuppressWarnings("deprecation")
                List<Address> addresses = geocoder.getFromLocation(point.latitude(), point.longitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
                    String thoroughfare = addr.getThoroughfare();
                    String subLocality = addr.getSubLocality();
                    String locality = addr.getLocality();
                    StringBuilder sb = new StringBuilder();
                    if (thoroughfare != null && !thoroughfare.isBlank()) {
                        sb.append(thoroughfare.trim());
                    }
                    if (subLocality != null && !subLocality.isBlank()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(subLocality.trim());
                    } else if (locality != null && !locality.isBlank()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(locality.trim());
                    }
                    String resolved = sb.toString().trim();
                    if (!resolved.isEmpty() && !resolved.equals(currentUserAreaName)) {
                        currentUserAreaName = resolved;
                        runOnUiThread(() -> {
                            txtUserGpsStatusBanner.setText(String.format(
                                    Locale.US,
                                    "Ubicacion Exacta GPS Activa (%s): (%.5f, %.5f)",
                                    currentUserAreaName,
                                    currentUserPreciseLocation.latitude(),
                                    currentUserPreciseLocation.longitude()
                            ));
                            EmergencyServiceType selectedService =
                                    (EmergencyServiceType) spinnerRegServiceType.getSelectedItem();
                            if (selectedService != null) {
                                populateNearbyStationsForService(selectedService, currentUserPreciseLocation);
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private void applyUserLocationToAllMaps(@NonNull GeoPoint userLocation, boolean updateAdminDefaultDest) {
        String areaSuffix = (currentUserAreaName != null && !currentUserAreaName.isBlank())
                ? (" - " + currentUserAreaName)
                : "";
        txtApplicantCurrentLocation.setText(String.format(
                Locale.US,
                "Ubicacion exacta actual del usuario%s: (%.5f, %.5f)",
                areaSuffix,
                userLocation.latitude(),
                userLocation.longitude()
        ));

        mapRegistrationStation.setMyLocationEnabledWithPermission(hasFineLocationPermission, userLocation);
        mapAdminDispatchDestination.setMyLocationEnabledWithPermission(hasFineLocationPermission, userLocation);
        routeMapCanvasView.setMyLocationEnabledWithPermission(hasFineLocationPermission, userLocation);

        EmergencyServiceType selectedService = (EmergencyServiceType) spinnerRegServiceType.getSelectedItem();
        if (selectedService != null) {
            populateNearbyStationsForService(selectedService, userLocation);
        }

        if (updateAdminDefaultDest) {
            selectedEmergencyDestinationPoint = new GeoPoint(
                    userLocation.latitude() + 0.0035,
                    userLocation.longitude() + 0.0035
            );
            updateAdminDestinationMarker(selectedEmergencyDestinationPoint);
        }
    }

    private void configureAuthAndRegistrationControls() {
        btnShowLoginTab.setOnClickListener(v -> showAuthSubTab(true));
        btnShowRegisterTab.setOnClickListener(v -> {
            showAuthSubTab(false);
            if (hasFineLocationPermission) {
                fetchCurrentDeviceLocationAndCenterMaps();
            }
        });

        Button btnLoginSubmit = findViewById(R.id.btnLoginSubmit);
        btnLoginSubmit.setOnClickListener(v -> registrationViewModel.loginWithDpiAndPassword(
                inputLoginDpi.getText().toString(),
                inputLoginPassword.getText().toString()
        ));

        // Iniciar sesion directamente con cualquier usuario seleccionado del Spinner sincronizado
        findViewById(R.id.btnLoginSelectedSpinnerUser).setOnClickListener(v -> {
            int pos = spinnerActiveSessionUser.getSelectedItemPosition();
            if (pos >= 0 && pos < allUsersCache.size()) {
                User selected = allUsersCache.get(pos);
                inputLoginDpi.setText(selected.getDpi());
                inputLoginPassword.setText(selected.getPassword());
                registrationViewModel.switchActiveSessionUser(selected);
            }
        });

        findViewById(R.id.btnDeleteSelectedUser).setOnClickListener(v -> {
            int pos = spinnerActiveSessionUser.getSelectedItemPosition();
            if (pos >= 0 && pos < allUsersCache.size()) {
                User target = allUsersCache.get(pos);
                registrationViewModel.deleteUser(target.getId(), database.emergencyOrderDao());
            }
        });
        findViewById(R.id.btnClearAndResetUsers).setOnClickListener(v ->
                registrationViewModel.clearAndResetAllUsers(database)
        );

        findViewById(R.id.btnConnectPeerIp).setOnClickListener(v -> {
            String ip = inputPeerDeviceIp.getText().toString().trim();
            if (!ip.isEmpty()) {
                syncManager.addManualPeerIp(ip);
                Toast.makeText(this, "IP " + ip + " agregada para sincronizacion directa.", Toast.LENGTH_SHORT).show();
            }
        });

        // Configuracion de registro en App Movil: Exclusivo para Usuarios Operativos (AGENTE)
        UserRole[] publicRoles = new UserRole[]{UserRole.AGENTE};
        ArrayAdapter<UserRole> roleAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                publicRoles
        );
        roleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRegRole.setAdapter(roleAdapter);
        containerAgentRegistrationFields.setVisibility(View.VISIBLE);

        EmergencyServiceType[] agentServices = new EmergencyServiceType[]{
                EmergencyServiceType.POLICIA,
                EmergencyServiceType.HOSPITAL,
                EmergencyServiceType.BOMBEROS
        };
        ArrayAdapter<EmergencyServiceType> serviceAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                agentServices
        );
        serviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRegServiceType.setAdapter(serviceAdapter);

        spinnerRegRole.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                UserRole role = publicRoles[position];
                if (role == UserRole.ADMINISTRADOR) {
                    containerAgentRegistrationFields.setVisibility(View.GONE);
                } else {
                    containerAgentRegistrationFields.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        spinnerRegServiceType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                EmergencyServiceType selectedService = agentServices[position];
                inputRegNip.setVisibility(
                        selectedService == EmergencyServiceType.POLICIA ? View.VISIBLE : View.GONE
                );
                selectedStationSpinnerIndex = 0;
                populateNearbyStationsForService(selectedService, currentUserPreciseLocation);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        mapRegistrationStation.setMapHeader(
                "Mapa Relativo a la Ubicacion Exacta del Usuario",
                "Muestra su posicion GPS actual y las estaciones dinamicas cercanas"
        );
        mapRegistrationStation.setOnMapCoordinatePickedListener(pickedPoint -> {
            currentUserPreciseLocation = pickedPoint;
            txtApplicantCurrentLocation.setText(String.format(
                    Locale.US,
                    "Ubicacion exacta seleccionada del usuario: (%.5f, %.5f)",
                    pickedPoint.latitude(),
                    pickedPoint.longitude()
            ));
            resolveAreaNameWithGeocoderAsync(pickedPoint);
            EmergencyServiceType currentService = (EmergencyServiceType) spinnerRegServiceType.getSelectedItem();
            if (currentService != null) {
                populateNearbyStationsForService(currentService, pickedPoint);
            }
        });

        Button btnRegisterUser = findViewById(R.id.btnRegisterUser);
        btnRegisterUser.setOnClickListener(v -> {
            UserRole selectedRole = (UserRole) spinnerRegRole.getSelectedItem();
            EmergencyServiceType selectedService = selectedRole == UserRole.AGENTE
                    ? (EmergencyServiceType) spinnerRegServiceType.getSelectedItem()
                    : EmergencyServiceType.ADMINISTRACION;

            String stationAddress = selectedRole == UserRole.AGENTE
                    ? inputRegAddress.getText().toString()
                    : "Centro de Despacho Administracion";

            String regDpi = UserRepository.normalizeDpi(inputRegDpi.getText().toString());
            String regPass = inputRegPassword.getText().toString().trim();
            if (regPass.isEmpty()) {
                regPass = User.DEFAULT_PASSWORD;
            }
            inputLoginDpi.setText(regDpi);
            inputLoginPassword.setText(regPass);

            double latToSave = selectedRole == UserRole.AGENTE
                    ? selectedStationGeoPoint.latitude()
                    : currentUserPreciseLocation.latitude();
            double lngToSave = selectedRole == UserRole.AGENTE
                    ? selectedStationGeoPoint.longitude()
                    : currentUserPreciseLocation.longitude();

            registrationViewModel.submitRegistrationForm(
                    inputRegFullName.getText().toString(),
                    regDpi,
                    regPass,
                    inputRegPhones.getText().toString(),
                    stationAddress,
                    inputRegNip.getText().toString(),
                    selectedRole,
                    selectedService,
                    selectedStationNodeId,
                    latToSave,
                    lngToSave
            );
        });
    }

    private void showAuthSubTab(boolean showLogin) {
        cardLoginContainer.setVisibility(showLogin ? View.VISIBLE : View.GONE);
        cardRegisterContainer.setVisibility(showLogin ? View.GONE : View.VISIBLE);
        if (!showLogin && mapRegistrationStation != null) {
            mapRegistrationStation.ensureMapViewInitialized();
        }
        btnShowLoginTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor(showLogin ? "#1E40AF" : "#475569")
        ));
        btnShowRegisterTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor(showLogin ? "#475569" : "#1E40AF")
        ));
    }

    /**
     * Genera dinamicamente las estaciones cercanas relativas a la ubicacion GPS exacta del usuario
     * y las vincula a los nodos viales reales de {@code corredor_suroriente.db}.
     */
    private void populateNearbyStationsForService(
            @NonNull EmergencyServiceType serviceType,
            @NonNull GeoPoint applicantPosition
    ) {
        // 1. Generacion inmediata en hilo UI relativa a applicantPosition para respuesta instantanea
        List<PresetStationCatalog.StationOption> immediateOptions =
                PresetStationCatalog.getDynamicStationsNearApplicant(
                        serviceType,
                        applicantPosition,
                        currentUserAreaName,
                        null
                );
        bindStationOptionsToSpinner(immediateOptions);

        // 2. Ajuste asincrono contra los nodos reales de graph_nodes en SQLite alrededor del usuario
        GeoRutaDatabase.databaseWriteExecutor.execute(() -> {
            List<PresetStationCatalog.StationOption> snappedOptions =
                    PresetStationCatalog.getDynamicStationsNearApplicant(
                            serviceType,
                            applicantPosition,
                            currentUserAreaName,
                            database.graphDao()
                    );
            runOnUiThread(() -> {
                EmergencyServiceType currentService =
                        (EmergencyServiceType) spinnerRegServiceType.getSelectedItem();
                if (currentService == serviceType) {
                    bindStationOptionsToSpinner(snappedOptions);
                }
            });
        });
    }

    private void bindStationOptionsToSpinner(@NonNull List<PresetStationCatalog.StationOption> options) {
        currentDynamicStationOptions = options;
        ArrayAdapter<PresetStationCatalog.StationOption> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                currentDynamicStationOptions
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerNearbyStations.setAdapter(adapter);

        int safeIndex = (selectedStationSpinnerIndex >= 0 && selectedStationSpinnerIndex < options.size())
                ? selectedStationSpinnerIndex
                : 0;
        if (!options.isEmpty()) {
            spinnerNearbyStations.setSelection(safeIndex, false);
            applySelectedStationOption(options.get(safeIndex));
        }

        spinnerNearbyStations.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < currentDynamicStationOptions.size()) {
                    selectedStationSpinnerIndex = position;
                    applySelectedStationOption(currentDynamicStationOptions.get(position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void applySelectedStationOption(@NonNull PresetStationCatalog.StationOption station) {
        selectedStationNodeId = station.nodeId();
        selectedStationGeoPoint = new GeoPoint(station.latitude(), station.longitude());
        inputRegAddress.setText(station.name());
        txtSelectedStationCoords.setText(String.format(
                Locale.US,
                "Estacion dinamica seleccionada: %s [%.5f, %.5f] (a %.2f km de su ubicacion)",
                station.name(),
                station.latitude(),
                station.longitude(),
                Math.max(0.0, station.distanceMetersFromApplicant()) / 1000.0
        ));
        mapRegistrationStation.setStationLocation(selectedStationGeoPoint, station.name());
    }

    private void configureAdminControls() {
        btnAdminTabDispatch.setOnClickListener(v -> showAdminSubTab(true));
        btnAdminTabSearchDpi.setOnClickListener(v -> showAdminSubTab(false));

        mapAdminDispatchDestination.setMapHeader(
                "Mapa de Despacho (Solo Punto de Llegada)",
                "Toque el mapa para marcar el punto exacto de la emergencia"
        );
        mapAdminDispatchDestination.setStationLocation(null, null);
        updateAdminDestinationMarker(selectedEmergencyDestinationPoint);
        mapAdminDispatchDestination.setOnMapCoordinatePickedListener(this::updateAdminDestinationMarker);

        Button btnDispatchEmergency = findViewById(R.id.btnDispatchEmergency);
        btnDispatchEmergency.setOnClickListener(v -> {
            List<EmergencyServiceType> selectedServices = collectSelectedDispatchServices();
            long adminId = (loggedInUser != null) ? loggedInUser.getId() : 2L;
            adminMonitorViewModel.dispatchToNearestStationsForServices(
                    adminId,
                    selectedServices,
                    selectedEmergencyDestinationPoint,
                    inputOrderDescription.getText().toString()
            );
        });

        Button btnTestDelayedDispatch = findViewById(R.id.btnTestDelayedDispatchAndSwitchToAgent);
        btnTestDelayedDispatch.setOnClickListener(v -> startRealtimeAdminToAgentDispatchSimulation());

        spinnerAdminQuickPickAgentDpi.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < agentUsersCache.size()) {
                    User agent = agentUsersCache.get(position);
                    inputSearchAgentDpi.setText(agent.getDpi());
                    adminMonitorViewModel.searchAgentByDpi(agent.getDpi());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Button btnSearchAgentByDpi = findViewById(R.id.btnSearchAgentByDpi);
        btnSearchAgentByDpi.setOnClickListener(v ->
                adminMonitorViewModel.searchAgentByDpi(inputSearchAgentDpi.getText().toString())
        );
    }

    private void showAdminSubTab(boolean showDispatch) {
        cardAdminDispatch.setVisibility(showDispatch ? View.VISIBLE : View.GONE);
        cardAdminSearchDpi.setVisibility(showDispatch ? View.GONE : View.VISIBLE);
        btnAdminTabDispatch.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor(showDispatch ? "#B91C1C" : "#475569")
        ));
        btnAdminTabSearchDpi.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor(showDispatch ? "#475569" : "#0F766E")
        ));
    }

    private List<EmergencyServiceType> collectSelectedDispatchServices() {
        List<EmergencyServiceType> services = new ArrayList<>();
        if (chkServicePolicia.isChecked()) {
            services.add(EmergencyServiceType.POLICIA);
        }
        if (chkServiceHospital.isChecked()) {
            services.add(EmergencyServiceType.HOSPITAL);
        }
        if (chkServiceBomberos.isChecked()) {
            services.add(EmergencyServiceType.BOMBEROS);
        }
        return services;
    }

    private void updateAdminDestinationMarker(@NonNull GeoPoint point) {
        selectedEmergencyDestinationPoint = point;
        mapAdminDispatchDestination.setStationLocation(null, null);
        mapAdminDispatchDestination.setDestinationLocation(point, "PUNTO DE LLEGADA EMERGENCIA");
        txtDispatchMapInstruction.setText(String.format(
                Locale.US,
                "Punto de llegada seleccionado: (%.5f, %.5f)",
                point.latitude(),
                point.longitude()
        ));
    }

    private void startRealtimeAdminToAgentDispatchSimulation() {
        List<EmergencyServiceType> selectedServices = collectSelectedDispatchServices();
        if (selectedServices.isEmpty()) {
            Toast.makeText(this, "Seleccione al menos un servicio (Policia, Hospital o Bomberos).", Toast.LENGTH_SHORT).show();
            return;
        }

        long adminId = (loggedInUser != null) ? loggedInUser.getId() : 2L;
        GeoPoint targetDestination = selectedEmergencyDestinationPoint;
        String description = inputOrderDescription.getText().toString();
        EmergencyServiceType primaryService = selectedServices.get(0);

        User targetAgent = findNearestApprovedAgentInCache(primaryService, targetDestination);
        if (targetAgent == null) {
            Toast.makeText(this, "No hay agentes aprobados para el servicio seleccionado.", Toast.LENGTH_SHORT).show();
            return;
        }

        registrationViewModel.switchActiveSessionUser(targetAgent);

        if (realtimeTestCountDownTimer != null) {
            realtimeTestCountDownTimer.cancel();
        }

        realtimeTestCountDownTimer = new CountDownTimer(4000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long sec = Math.max(1L, millisUntilFinished / 1000L);
                txtAgentRealtimeCountdown.setText(
                        "SIMULACION EN TIEMPO REAL: El Administrador enviara una orden a su unidad en " + sec + " s..."
                );
            }

            @Override
            public void onFinish() {
                txtAgentRealtimeCountdown.setText("Recibiendo despacho de emergencia desde la central...");
                adminMonitorViewModel.dispatchToNearestStationsForServices(
                        adminId,
                        selectedServices,
                        targetDestination,
                        description
                );
            }
        }.start();
    }

    @Nullable
    private User findNearestApprovedAgentInCache(
            @NonNull EmergencyServiceType serviceType,
            @NonNull GeoPoint destination
    ) {
        User nearest = null;
        double minDistance = Double.MAX_VALUE;
        for (User u : allUsersCache) {
            if (u.getRole() == UserRole.AGENTE
                    && u.getApprovalStatus() == ApprovalStatus.APPROVED
                    && u.getServiceType() == serviceType) {
                double d = new GeoPoint(u.getStationLat(), u.getStationLng()).distanceToMeters(destination);
                if (d < minDistance) {
                    minDistance = d;
                    nearest = u;
                }
            }
        }
        return nearest;
    }

    private void configureAgentControls() {
        routeMapCanvasView.setMapHeader(
                "Mapa Operativo del Usuario (Estacion y Ruta)",
                "Visualiza su estacion asignada y dibuja las ordenes recibidas"
        );

        btnToggleAgentProfile.setOnClickListener(v -> {
            boolean currentlyVisible = cardAgentProfile.getVisibility() == View.VISIBLE;
            cardAgentProfile.setVisibility(currentlyVisible ? View.GONE : View.VISIBLE);
            btnToggleAgentProfile.setText(
                    currentlyVisible ? "Ver Mi Perfil y Estacion Asignada" : "Ocultar Mi Perfil"
            );
        });

        findViewById(R.id.btnZoomInMap).setOnClickListener(v -> routeMapCanvasView.zoomBy(0.70));
        findViewById(R.id.btnZoomOutMap).setOnClickListener(v -> routeMapCanvasView.zoomBy(1.40));
        findViewById(R.id.btnResetZoomMap).setOnClickListener(v -> routeMapCanvasView.resetZoom());

        Button btnAutoWalk = findViewById(R.id.btnAutoWalkRoute);
        btnAutoWalk.setOnClickListener(v -> {
            if (isAutoWalkingRoute) {
                stopAutoWalkRouteSimulation();
            } else {
                startAutoWalkRouteSimulation();
            }
        });

        findViewById(R.id.btnSimulateAdvanceStep).setOnClickListener(v -> {
            AgentNavigationViewModel.NavigationUiState state =
                    agentNavigationViewModel.getNavigationState().getValue();
            if (state == null || state.activePolylinePoints().isEmpty()) {
                return;
            }
            List<GeoPoint> poly = state.activePolylinePoints();
            GeoPoint nextPoint = poly.size() > 1 ? poly.get(1) : poly.get(0);
            agentNavigationViewModel.onRealTimeLocationUpdate(nextPoint);
        });

        findViewById(R.id.btnSimulateDeviation).setOnClickListener(v -> {
            AgentNavigationViewModel.NavigationUiState state =
                    agentNavigationViewModel.getNavigationState().getValue();
            if (state == null || state.currentAgentLocation() == null) {
                return;
            }
            GeoPoint cur = state.currentAgentLocation();
            GeoPoint deviatedPoint = new GeoPoint(cur.latitude() - 0.0022, cur.longitude() - 0.0022);
            agentNavigationViewModel.onRealTimeLocationUpdate(deviatedPoint);
        });

        findViewById(R.id.btnCompleteEmergencyOrder).setOnClickListener(v -> {
            stopAutoWalkRouteSimulation();
            if (currentActiveAgentOrder != null) {
                long orderIdToFinish = currentActiveAgentOrder.getId();
                emergencyRepository.updateOrderStatus(
                        orderIdToFinish,
                        OrderStatus.COMPLETED,
                        new EmergencyRepository.RepositoryCallback<>() {
                            @Override
                            public void onSuccess(Boolean result) {
                                Toast.makeText(
                                        PocDashboardActivity.this,
                                        "Mision finalizada por el Agente y sincronizada con el Centro de Comando.",
                                        Toast.LENGTH_SHORT
                                ).show();
                            }

                            @Override
                            public void onError(Throwable error) {
                            }
                        }
                );
            }
        });
    }

    private void startAutoWalkRouteSimulation() {
        AgentNavigationViewModel.NavigationUiState state =
                agentNavigationViewModel.getNavigationState().getValue();
        if (state == null || state.activePolylinePoints().isEmpty()) {
            Toast.makeText(this, "No hay una ruta activa para recorrer.", Toast.LENGTH_SHORT).show();
            return;
        }
        isAutoWalkingRoute = true;
        Button btnAutoWalk = findViewById(R.id.btnAutoWalkRoute);
        if (btnAutoWalk != null) {
            btnAutoWalk.setText("Pausar Recorrido Automatico sobre el Camino");
            btnAutoWalk.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#B45309")
            ));
        }
        autoWalkHandler.removeCallbacks(autoWalkRunnable);
        autoWalkHandler.post(autoWalkRunnable);
    }

    private void stopAutoWalkRouteSimulation() {
        isAutoWalkingRoute = false;
        autoWalkHandler.removeCallbacks(autoWalkRunnable);
        Button btnAutoWalk = findViewById(R.id.btnAutoWalkRoute);
        if (btnAutoWalk != null) {
            btnAutoWalk.setText("Simular Recorrido Automatico sobre el Camino");
            btnAutoWalk.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#0284C7")
            ));
        }
    }

    private void observeViewModels() {
        syncManager.getSyncStatusLiveData().observe(this, statusText -> {
            if (statusText != null) {
                txtSyncStatusBanner.setText(statusText);
            }
        });

        registrationViewModel.observeAllUsers().observe(this, users -> {
            allUsersCache.clear();
            agentUsersCache.clear();
            if (users != null) {
                for (User u : users) {
                    if (u.getRole() == UserRole.AGENTE) {
                        allUsersCache.add(u);
                        agentUsersCache.add(u);
                    }
                }
            }

            StringBuilder sbAll = new StringBuilder();
            StringBuilder sbOnlineAgents = new StringBuilder("UNIDADES EN LINEA SINCRONIZADAS:\n");
            List<String> allLabels = new ArrayList<>();
            List<String> agentLabels = new ArrayList<>();

            for (User u : agentUsersCache) {
                String roleOrService = "AGENTE - " + u.getServiceType().getDisplayName();
                String line = String.format(
                        Locale.US,
                        "DPI: %s | %s (%s)",
                        u.getDpi(),
                        u.getFullName(),
                        roleOrService
                );
                allLabels.add(line);
                sbAll.append("- ").append(line).append("\n");

                String agentLine = String.format(
                        Locale.US,
                        "[%s] DPI: %s - %s (%s)",
                        u.getServiceType().name(),
                        u.getDpi(),
                        u.getFullName(),
                        u.getAddress()
                );
                agentLabels.add(agentLine);
                sbOnlineAgents.append("- ").append(agentLine).append("\n");
            }

            txtRegisteredUsersListSummary.setText(
                    sbAll.length() > 0 ? sbAll.toString().trim() : "No hay usuarios operativos registrados."
            );
            txtAdminOnlineUnitsSummary.setText(
                    agentUsersCache.isEmpty() ? "Sin agentes registrados." : sbOnlineAgents.toString().trim()
            );

            ArrayAdapter<String> allAdapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    allLabels
            );
            allAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerActiveSessionUser.setAdapter(allAdapter);

            ArrayAdapter<String> agentAdapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    agentLabels
            );
            agentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerAdminQuickPickAgentDpi.setAdapter(agentAdapter);
        });

        registrationViewModel.getAuthError().observe(this, errorMsg -> {
            if (errorMsg != null && !errorMsg.isBlank()) {
                txtLoginStatusFeedback.setText("Error de inicio de sesion: " + errorMsg);
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
            }
        });

        registrationViewModel.getAuthenticatedUser().observe(this, this::applyRoleBasedWorkspaceForUser);

        registrationViewModel.getUiState().observe(this, state -> {
            if (state.errorMessage() != null) {
                txtRegistrationStatus.setText("Error: " + state.errorMessage());
                txtLoginStatusFeedback.setText("Error: " + state.errorMessage());
                Toast.makeText(this, state.errorMessage(), Toast.LENGTH_LONG).show();
            } else if (state.statusMessage() != null) {
                txtRegistrationStatus.setText(state.statusMessage());
                txtLoginStatusFeedback.setText(state.statusMessage());
                Toast.makeText(this, state.statusMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        adminMonitorViewModel.getDispatchUiState().observe(this, state -> {
            if (state.errorMessage() != null) {
                txtDispatchFeedback.setText("Error de despacho: " + state.errorMessage());
                Toast.makeText(this, state.errorMessage(), Toast.LENGTH_LONG).show();
            } else if (state.feedbackMessage() != null) {
                txtDispatchFeedback.setText(state.feedbackMessage());
                Toast.makeText(this, "Orden despachada y sincronizada en tiempo real.", Toast.LENGTH_SHORT).show();
            }
        });

        adminMonitorViewModel.getSearchDpiFeedback().observe(this, msg -> {
            if (msg != null) {
                txtSearchDpiStatus.setText(msg);
            }
        });

        adminMonitorViewModel.getSearchedAgentByDpi().observe(this, foundUser -> {
            if (foundUser == null) {
                txtSearchedAgentProfile.setText("Informacion del Agente: sin resultados para el DPI consultado.");
                txtAuditHistoryList.setText("Sin ordenes para mostrar.");
            } else {
                txtSearchedAgentProfile.setText(String.format(
                        Locale.US,
                        "FICHA DEL AGENTE LOCALIZADO:\n"
                                + "- Nombre: %s (ID #%d)\n"
                                + "- DPI: %s | Telefono: %s\n"
                                + "- Rol: %s | Servicio: %s\n"
                                + "- NIP Policial: %s | Estado: %s\n"
                                + "- Estacion Base: %s (%.5f, %.5f)",
                        foundUser.getFullName(),
                        foundUser.getId(),
                        foundUser.getDpi(),
                        foundUser.getPhones(),
                        foundUser.getRole().name(),
                        foundUser.getServiceType().getDisplayName(),
                        foundUser.getNip() != null ? foundUser.getNip() : "N/A",
                        foundUser.getApprovalStatus().name(),
                        foundUser.getAddress(),
                        foundUser.getStationLat(),
                        foundUser.getStationLng()
                ));
            }
        });

        adminMonitorViewModel.getSelectedAgentAuditHistory().observe(this, orders -> {
            if (orders == null || orders.isEmpty()) {
                txtAuditHistoryList.setText("El agente consultado no registra ordenes de emergencia.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (EmergencyOrder o : orders) {
                sb.append(String.format(
                        Locale.US,
                        "Orden #%d | Estado: %s | Desvios: %d\n"
                                + "  Llegada: (%.5f, %.5f) | %s\n",
                        o.getId(),
                        o.getStatus().name(),
                        o.getRecalculationCount(),
                        o.getDestinationLat(),
                        o.getDestinationLng(),
                        o.getDescription() != null ? o.getDescription() : "Sin descripcion"
                ));
            }
            txtAuditHistoryList.setText(sb.toString().trim());
        });

        agentNavigationViewModel.observeActiveOrder().observe(this, activeOrder -> {
            currentActiveAgentOrder = activeOrder;
            cardAgentActiveEmergency.setVisibility(View.VISIBLE);

            View btnComplete = findViewById(R.id.btnCompleteEmergencyOrder);
            View btnAutoWalk = findViewById(R.id.btnAutoWalkRoute);
            View btnAdvance = findViewById(R.id.btnSimulateAdvanceStep);
            View btnDeviate = findViewById(R.id.btnSimulateDeviation);

            if (activeOrder == null) {
                stopAutoWalkRouteSimulation();
                cardAgentNoEvents.setVisibility(View.VISIBLE);
                if (btnComplete != null) btnComplete.setVisibility(View.GONE);
                if (btnAutoWalk != null) btnAutoWalk.setVisibility(View.GONE);
                if (btnAdvance != null) btnAdvance.setVisibility(View.GONE);
                if (btnDeviate != null) btnDeviate.setVisibility(View.GONE);

                String stationLabel = (loggedInUser != null)
                        ? String.format(
                                Locale.US,
                                "ESTACION ASIGNADA EN MAPA: %s (%.5f, %.5f) - En espera de ordenes",
                                loggedInUser.getAddress(),
                                loggedInUser.getStationLat(),
                                loggedInUser.getStationLng()
                        )
                        : "ESTACION ASIGNADA EN MAPA - Sin ordenes activas";
                txtActiveEmergencyOrderBanner.setBackgroundColor(Color.parseColor("#E0F2FE"));
                txtActiveEmergencyOrderBanner.setTextColor(Color.parseColor("#0369A1"));
                txtActiveEmergencyOrderBanner.setText(stationLabel);
                txtAgentRealtimeCountdown.setText(
                        "Su estacion asignada se muestra en el mapa de abajo. Cuando el Administrador despache una orden desde la PC, la ruta se dibujara directamente sobre este mismo mapa."
                );

                routeMapCanvasView.setDestinationLocation(null, null);
                agentNavigationViewModel.clearActiveNavigation();
                if (loggedInUser != null) {
                    GeoPoint st = new GeoPoint(loggedInUser.getStationLat(), loggedInUser.getStationLng());
                    routeMapCanvasView.setStationLocation(st, loggedInUser.getAddress());
                }
            } else {
                cardAgentNoEvents.setVisibility(View.GONE);
                if (btnComplete != null) btnComplete.setVisibility(View.VISIBLE);
                if (btnAutoWalk != null) btnAutoWalk.setVisibility(View.VISIBLE);
                if (btnAdvance != null) btnAdvance.setVisibility(View.VISIBLE);
                if (btnDeviate != null) btnDeviate.setVisibility(View.VISIBLE);

                txtActiveEmergencyOrderBanner.setBackgroundColor(Color.parseColor("#FEF2F2"));
                txtActiveEmergencyOrderBanner.setTextColor(Color.parseColor("#7F1D1D"));
                txtActiveEmergencyOrderBanner.setText(String.format(
                        Locale.US,
                        "ORDEN ACTIVA #%d | %s\nCoordenadas de llegada: (%.5f, %.5f)",
                        activeOrder.getId(),
                        activeOrder.getDescription() != null ? activeOrder.getDescription() : "Emergencia SOS",
                        activeOrder.getDestinationLat(),
                        activeOrder.getDestinationLng()
                ));
                if (loggedInUser != null) {
                    routeMapCanvasView.setStationLocation(
                            new GeoPoint(loggedInUser.getStationLat(), loggedInUser.getStationLng()),
                            loggedInUser.getAddress()
                    );
                }
                routeMapCanvasView.setDestinationLocation(
                        new GeoPoint(activeOrder.getDestinationLat(), activeOrder.getDestinationLng()),
                        "EMERGENCIA #" + activeOrder.getId()
                );

                AgentNavigationViewModel.NavigationUiState navState =
                        agentNavigationViewModel.getNavigationState().getValue();
                if (navState == null || !Long.valueOf(activeOrder.getId()).equals(navState.activeOrderId())) {
                    agentNavigationViewModel.onEmergencyOrderReceived(activeOrder);
                }
            }
        });

        agentNavigationViewModel.getNavigationState().observe(this, state -> {
            routeMapCanvasView.updateRouteState(
                    state.activePolylinePoints(),
                    state.currentAgentLocation(),
                    state.wasRecalculatedDueToDeviation()
            );

            String locStr = state.currentAgentLocation() != null
                    ? String.format(
                            Locale.US,
                            "(%.5f, %.5f)",
                            state.currentAgentLocation().latitude(),
                            state.currentAgentLocation().longitude()
                    )
                    : "Esperando GPS";

            txtNavigationTelemetry.setText(String.format(
                    Locale.US,
                    "Orden Activa: %s | Puntos Polyline restantes: %d\n"
                            + "Ubicacion Agente: %s | Desviacion: %.1f m\n"
                            + "Recalculado con Dijkstra: %s\n"
                            + "Estado: %s",
                    state.activeOrderId() != null ? "#" + state.activeOrderId() : "Ninguna",
                    state.activePolylinePoints().size(),
                    locStr,
                    state.distanceToRouteMeters(),
                    state.wasRecalculatedDueToDeviation() ? "SI" : "NO",
                    state.errorMessage() != null ? state.errorMessage()
                            : (state.statusMessage() != null ? state.statusMessage() : "En espera")
            ));
        });
    }

    private void applyRoleBasedWorkspaceForUser(@Nullable User user) {
        this.loggedInUser = user;

        if (user == null) {
            btnLogoutSession.setVisibility(View.GONE);
            txtActiveIdentityBanner.setText("Sesion no iniciada. Ingrese su DPI y clave o cree un usuario.");
            layoutAuthScreen.setVisibility(View.VISIBLE);
            layoutAdminWorkspace.setVisibility(View.GONE);
            layoutAgentWorkspace.setVisibility(View.GONE);
            return;
        }

        btnLogoutSession.setVisibility(View.VISIBLE);
        layoutAuthScreen.setVisibility(View.GONE);

        if (user.getRole() == UserRole.ADMINISTRADOR || user.getRole() == UserRole.SUPER_USUARIO) {
            registrationViewModel.logout();
            String msg = "El rol ADMINISTRADOR se utiliza exclusivamente desde la Aplicacion Web en PC (http://localhost:8080). La app movil es exclusiva para Usuarios Operativos (Agentes).";
            txtLoginStatusFeedback.setText(msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        } else {
            txtActiveIdentityBanner.setText(String.format(
                    Locale.US,
                    "SESION AGENTE (%s): %s | DPI: %s",
                    user.getServiceType().getDisplayName(),
                    user.getFullName(),
                    user.getDpi()
            ));
            layoutAdminWorkspace.setVisibility(View.GONE);
            layoutAgentWorkspace.setVisibility(View.VISIBLE);

            int badgeColor = switch (user.getServiceType()) {
                case POLICIA -> Color.parseColor("#0369A1");
                case HOSPITAL -> Color.parseColor("#0D9488");
                case BOMBEROS -> Color.parseColor("#B45309");
                case ADMINISTRACION -> Color.parseColor("#334155");
            };
            txtAgentRecognizedServiceBadge.setBackgroundColor(badgeColor);
            txtAgentRecognizedServiceBadge.setText(String.format(
                    Locale.US,
                    "UNIDAD RECONOCIDA: %s\nAgente: %s | DPI: %s | Estacion: %s",
                    user.getServiceType().getDisplayName().toUpperCase(Locale.US),
                    user.getFullName(),
                    user.getDpi(),
                    user.getAddress()
            ));

            txtAgentOwnProfileDetails.setText(String.format(
                    Locale.US,
                    "Nombre Completo: %s\n"
                            + "DPI: %s\n"
                            + "Rol Operativo: %s\n"
                            + "Servicio de Emergencia: %s\n"
                            + "NIP Policial: %s\n"
                            + "Telefono: %s\n"
                            + "Estacion Asignada: %s\n"
                            + "Coordenadas de Estacion: (%.5f, %.5f)",
                    user.getFullName(),
                    user.getDpi(),
                    user.getRole().name(),
                    user.getServiceType().getDisplayName(),
                    user.getNip() != null ? user.getNip() : "No aplica",
                    user.getPhones(),
                    user.getAddress(),
                    user.getStationLat(),
                    user.getStationLng()
            ));
            txtAgentStandbySubtitle.setText(String.format(
                    Locale.US,
                    "Unidad activa reconocida como %s en %s. No hay ningun suceso activo en este momento.",
                    user.getServiceType().getDisplayName(),
                    user.getAddress()
            ));

            GeoPoint stationPoint = new GeoPoint(user.getStationLat(), user.getStationLng());
            routeMapCanvasView.ensureMapViewInitialized();
            routeMapCanvasView.setStationLocation(stationPoint, user.getAddress());
            agentNavigationViewModel.startListeningOrdersForAgent(user.getId());
        }
    }
}
