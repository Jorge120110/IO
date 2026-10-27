package com.example.georutassos.data.repository;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.georutassos.data.local.dao.EmergencyOrderDao;
import com.example.georutassos.data.local.dao.UserDao;
import com.example.georutassos.data.local.database.DemoSeedDataInitializer;
import com.example.georutassos.data.local.database.GeoRutaDatabase;
import com.example.georutassos.data.local.entity.EmergencyOrder;
import com.example.georutassos.data.local.entity.User;
import com.example.georutassos.data.sync.RealtimeCrossDeviceSyncManager;
import com.example.georutassos.domain.model.ApprovalStatus;
import com.example.georutassos.domain.model.EmergencyServiceType;
import com.example.georutassos.domain.model.GeoPoint;
import com.example.georutassos.domain.model.OrderStatus;
import com.example.georutassos.domain.model.UserRole;
import com.example.georutassos.domain.notification.NotificationSimulator;
import com.example.georutassos.domain.routing.DijkstraRouteEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Repositorio de Emergencias y Ruteo (Modulos 2 y 3).
 * Gestiona el despacho automatico hacia la estacion mas cercana por tipo de servicio
 * (Policia, Hospital, Bomberos), las consultas de historial por agente y el calculo con Dijkstra.
 */
public class EmergencyRepository {

    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onError(Throwable error);
    }

    /** Resultado de asignacion automatica a la estacion mas cercana de un servicio. */
    public record ServiceDispatchAssignment(
            @NonNull EmergencyServiceType serviceType,
            @NonNull User assignedAgent,
            @NonNull EmergencyOrder order,
            double distanceToEmergencyMeters
    ) {}

    private final EmergencyOrderDao emergencyOrderDao;
    private final UserDao userDao;
    private final DijkstraRouteEngine dijkstraRouteEngine;
    private final NotificationSimulator notificationSimulator;
    private final ExecutorService ioExecutor;
    @Nullable
    private RealtimeCrossDeviceSyncManager syncManager;

    private final Map<Long, MediatorLiveData<List<EmergencyOrder>>> historyByAgentMap = new ConcurrentHashMap<>();
    private final Map<Long, MediatorLiveData<EmergencyOrder>> activeOrderByAgentMap = new ConcurrentHashMap<>();

    public EmergencyRepository(
            @NonNull EmergencyOrderDao emergencyOrderDao,
            @NonNull UserDao userDao,
            @NonNull DijkstraRouteEngine dijkstraRouteEngine,
            @NonNull NotificationSimulator notificationSimulator
    ) {
        this(
                emergencyOrderDao,
                userDao,
                dijkstraRouteEngine,
                notificationSimulator,
                GeoRutaDatabase.databaseWriteExecutor
        );
    }

    public EmergencyRepository(
            @NonNull EmergencyOrderDao emergencyOrderDao,
            @NonNull UserDao userDao,
            @NonNull DijkstraRouteEngine dijkstraRouteEngine,
            @NonNull NotificationSimulator notificationSimulator,
            @NonNull ExecutorService ioExecutor
    ) {
        this.emergencyOrderDao = emergencyOrderDao;
        this.userDao = userDao;
        this.dijkstraRouteEngine = dijkstraRouteEngine;
        this.notificationSimulator = notificationSimulator;
        this.ioExecutor = ioExecutor;
    }

    private static <T> void setOrPost(@NonNull MutableLiveData<T> liveData, @Nullable T value) {
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null && Looper.myLooper() == mainLooper) {
            liveData.setValue(value);
        } else {
            liveData.postValue(value);
        }
    }

    private static void runOnMainThreadIfPossible(@NonNull Runnable action) {
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper == null || Looper.myLooper() == mainLooper) {
            action.run();
        } else {
            new Handler(mainLooper).post(action);
        }
    }

    private static <T> void deliverSuccess(@Nullable RepositoryCallback<T> callback, T result) {
        if (callback == null) {
            return;
        }
        runOnMainThreadIfPossible(() -> callback.onSuccess(result));
    }

    private static <T> void deliverError(@Nullable RepositoryCallback<T> callback, Throwable error) {
        if (callback == null) {
            return;
        }
        runOnMainThreadIfPossible(() -> callback.onError(error));
    }

    public void setSyncManager(@Nullable RealtimeCrossDeviceSyncManager syncManager) {
        this.syncManager = syncManager;
    }

    public void refreshAgentOrdersAsync(long agentId) {
        ioExecutor.execute(() -> refreshAgentOrdersSync(agentId));
    }

    private void refreshAgentOrdersSync(long agentId) {
        try {
            MediatorLiveData<List<EmergencyOrder>> hist = historyByAgentMap.get(agentId);
            if (hist != null) {
                setOrPost(hist, emergencyOrderDao.getOrderHistoryForAgentSync(agentId));
            }
            MediatorLiveData<EmergencyOrder> active = activeOrderByAgentMap.get(agentId);
            if (active != null) {
                setOrPost(active, emergencyOrderDao.getActiveOrderForAgentSync(agentId));
            }
        } catch (Throwable ignored) {
        }
    }

    public void refreshAllCachedAgentsAsync() {
        ioExecutor.execute(this::refreshAllCachedAgentsSync);
    }

    private void refreshAllCachedAgentsSync() {
        for (Long agentId : historyByAgentMap.keySet()) {
            refreshAgentOrdersSync(agentId);
        }
        for (Long agentId : activeOrderByAgentMap.keySet()) {
            if (!historyByAgentMap.containsKey(agentId)) {
                refreshAgentOrdersSync(agentId);
            }
        }
    }

    /**
     * Despacha automaticamente ordenes de emergencia hacia la estacion aprobada mas cercana
     * de cada servicio solicitado (1, 2 o los 3 servicios: POLICIA, HOSPITAL, BOMBEROS).
     */
    public void dispatchOrderToNearestStations(
            long dispatcherAdminId,
            @NonNull List<EmergencyServiceType> requestedServices,
            @NonNull GeoPoint destinationCoordinates,
            @Nullable String description,
            @NonNull RepositoryCallback<List<ServiceDispatchAssignment>> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                DemoSeedDataInitializer.ensureBaseUsersExistSync(userDao);
                if (syncManager != null) {
                    syncManager.pullLatestStateBeforeQuerySync();
                }

                User admin = userDao.getUserByIdSync(dispatcherAdminId);
                if (admin == null || (admin.getRole() != UserRole.ADMINISTRADOR && admin.getRole() != UserRole.SUPER_USUARIO)) {
                    admin = userDao.getFirstAdminUserSync();
                }
                long effectiveAdminId = (admin != null && admin.getId() > 0)
                        ? admin.getId()
                        : (dispatcherAdminId > 0 ? dispatcherAdminId : 1L);

                if (requestedServices.isEmpty()) {
                    deliverError(callback, new IllegalArgumentException("Debe seleccionar al menos un servicio a enviar (Policia, Hospital o Bomberos)."));
                    return;
                }

                long destinationNodeId = dijkstraRouteEngine.findNearestNodeId(destinationCoordinates);
                if (destinationNodeId <= 0) {
                    destinationNodeId = 133313L;
                }

                List<ServiceDispatchAssignment> assignments = new ArrayList<>();
                List<String> missingServices = new ArrayList<>();
                List<String> busyServices = new ArrayList<>();

                for (EmergencyServiceType serviceType : requestedServices) {
                    List<User> candidates = userDao.getApprovedAgentsByServiceSync(serviceType);
                    if (candidates == null || candidates.isEmpty()) {
                        missingServices.add(serviceType.getDisplayName());
                        continue;
                    }

                    User nearestAgent = null;
                    double minDistanceMeters = Double.MAX_VALUE;
                    int busyCountForService = 0;
                    for (User candidate : candidates) {
                        EmergencyOrder existingActive = emergencyOrderDao.getActiveOrderForAgentSync(candidate.getId());
                        if (existingActive != null) {
                            busyCountForService++;
                            continue;
                        }
                        GeoPoint stationPt = new GeoPoint(candidate.getStationLat(), candidate.getStationLng());
                        double dist = stationPt.distanceToMeters(destinationCoordinates);
                        if (dist < minDistanceMeters) {
                            minDistanceMeters = dist;
                            nearestAgent = candidate;
                        }
                    }

                    if (nearestAgent == null && busyCountForService > 0) {
                        busyServices.add(serviceType.getDisplayName());
                        continue;
                    }

                    if (nearestAgent != null) {
                        GeoPoint agentStationPoint = new GeoPoint(
                                nearestAgent.getStationLat(),
                                nearestAgent.getStationLng()
                        );
                        long originNodeId = nearestAgent.getStationNodeId();
                        if (originNodeId <= 0) {
                            originNodeId = dijkstraRouteEngine.findNearestNodeId(agentStationPoint);
                        }
                        if (originNodeId <= 0) {
                            originNodeId = User.DEFAULT_STATION_NODE_ID;
                        }

                        String serviceOrderDesc = "[" + serviceType.getDisplayName() + "] "
                                + (description != null ? description : "Emergencia SOS");

                        EmergencyOrder order = new EmergencyOrder(
                                nearestAgent.getId(),
                                effectiveAdminId,
                                originNodeId,
                                nearestAgent.getStationLat(),
                                nearestAgent.getStationLng(),
                                destinationNodeId,
                                destinationCoordinates.latitude(),
                                destinationCoordinates.longitude(),
                                serviceOrderDesc,
                                OrderStatus.ASSIGNED,
                                System.currentTimeMillis() + assignments.size(),
                                null,
                                0
                        );

                        long orderId = emergencyOrderDao.insertOrder(order);
                        order.setId(orderId);

                        refreshAgentOrdersSync(nearestAgent.getId());
                        notificationSimulator.sendEmergencyDispatchAlert(order);
                        assignments.add(new ServiceDispatchAssignment(
                                serviceType,
                                nearestAgent,
                                order,
                                minDistanceMeters
                        ));
                    }
                }

                if (assignments.isEmpty()) {
                    if (!busyServices.isEmpty()) {
                        deliverError(callback, new IllegalStateException(
                                "No se puede asignar una segunda ruta a [" + String.join(", ", busyServices)
                                        + "]: Todas las unidades activas de ese servicio ya se encuentran atendiendo una emergencia. "
                                        + "El agente debe finalizar su mision actual desde la App Movil antes de recibir otro despacho."
                        ));
                        return;
                    }
                    deliverError(callback, new IllegalStateException(
                            "No hay estaciones/agentes aprobados disponibles para: " + String.join(", ", missingServices)
                    ));
                    return;
                }

                if (syncManager != null) {
                    syncManager.syncNowAsync(true);
                }

                deliverSuccess(callback, assignments);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public void assignEmergencyOrder(
            long dispatcherAdminId,
            long targetAgentId,
            long originNodeId,
            @NonNull GeoPoint originCoordinates,
            long destinationNodeId,
            @NonNull GeoPoint destinationCoordinates,
            @Nullable String description,
            @NonNull RepositoryCallback<EmergencyOrder> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                User admin = userDao.getUserByIdSync(dispatcherAdminId);
                if (admin == null || (admin.getRole() != UserRole.ADMINISTRADOR && admin.getRole() != UserRole.SUPER_USUARIO)) {
                    deliverError(callback, new SecurityException("Solo un ADMINISTRADOR o SUPER_USUARIO puede despachar ordenes de emergencia."));
                    return;
                }

                User agent = userDao.getUserByIdSync(targetAgentId);
                if (agent == null || agent.getRole() != UserRole.AGENTE || agent.getApprovalStatus() != ApprovalStatus.APPROVED) {
                    deliverError(callback, new IllegalStateException("El agente seleccionado no es valido o no esta aprobado."));
                    return;
                }

                EmergencyOrder order = new EmergencyOrder(
                        targetAgentId,
                        dispatcherAdminId,
                        originNodeId,
                        originCoordinates.latitude(),
                        originCoordinates.longitude(),
                        destinationNodeId,
                        destinationCoordinates.latitude(),
                        destinationCoordinates.longitude(),
                        description,
                        OrderStatus.ASSIGNED,
                        System.currentTimeMillis(),
                        null,
                        0
                );

                long orderId = emergencyOrderDao.insertOrder(order);
                order.setId(orderId);

                refreshAgentOrdersSync(targetAgentId);
                notificationSimulator.sendEmergencyDispatchAlert(order);
                deliverSuccess(callback, order);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public LiveData<List<EmergencyOrder>> observeAgentAuditHistory(long agentId) {
        MediatorLiveData<List<EmergencyOrder>> liveData = historyByAgentMap.computeIfAbsent(agentId, id -> {
            MediatorLiveData<List<EmergencyOrder>> mediator = new MediatorLiveData<>();
            runOnMainThreadIfPossible(() -> mediator.addSource(
                    emergencyOrderDao.observeOrderHistoryForAgent(id),
                    list -> setOrPost(mediator, list)
            ));
            return mediator;
        });
        refreshAgentOrdersAsync(agentId);
        return liveData;
    }

    public LiveData<List<EmergencyOrder>> observeAllOrdersHistory() {
        return emergencyOrderDao.observeAllOrdersHistory();
    }

    public LiveData<EmergencyOrder> observeActiveOrderForAgent(long agentId) {
        MediatorLiveData<EmergencyOrder> liveData = activeOrderByAgentMap.computeIfAbsent(agentId, id -> {
            MediatorLiveData<EmergencyOrder> mediator = new MediatorLiveData<>();
            runOnMainThreadIfPossible(() -> mediator.addSource(
                    emergencyOrderDao.observeActiveOrderForAgent(id),
                    order -> setOrPost(mediator, order)
            ));
            return mediator;
        });
        refreshAgentOrdersAsync(agentId);
        return liveData;
    }

    public void calculateRouteBetweenNodes(
            long startNodeId,
            long endNodeId,
            @NonNull RepositoryCallback<List<GeoPoint>> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                List<GeoPoint> route = dijkstraRouteEngine.calculateShortestPath(startNodeId, endNodeId);
                deliverSuccess(callback, route);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public void calculateRouteForOrder(
            @NonNull EmergencyOrder order,
            @NonNull RepositoryCallback<List<GeoPoint>> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                GeoPoint originPt = new GeoPoint(order.getOriginLat(), order.getOriginLng());
                GeoPoint destPt = new GeoPoint(order.getDestinationLat(), order.getDestinationLng());
                long startId = order.getOriginNodeId();
                long endId = order.getDestinationNodeId();
                if (startId <= 0) {
                    startId = dijkstraRouteEngine.findNearestNodeId(originPt);
                }
                if (endId <= 0 || endId == startId) {
                    endId = dijkstraRouteEngine.findNearestNodeId(destPt);
                }
                List<GeoPoint> route = dijkstraRouteEngine.calculateShortestPath(startId, endId);
                if (route == null || route.size() <= 2) {
                    long snappedStart = dijkstraRouteEngine.findNearestNodeId(originPt);
                    long snappedEnd = dijkstraRouteEngine.findNearestNodeId(destPt);
                    if (snappedStart > 0 && snappedEnd > 0) {
                        route = dijkstraRouteEngine.calculateShortestPath(snappedStart, snappedEnd);
                    }
                }
                deliverSuccess(callback, route);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public void recalculateRouteFromCurrentLocation(
            long activeOrderId,
            @NonNull GeoPoint currentAgentLocation,
            long destinationNodeId,
            @NonNull RepositoryCallback<List<GeoPoint>> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                long nearestNodeId = dijkstraRouteEngine.findNearestNodeId(currentAgentLocation);
                if (nearestNodeId < 0) {
                    deliverError(callback, new IllegalStateException("No se encontraron nodos en el grafo local para recalcular."));
                    return;
                }

                if (activeOrderId > 0) {
                    emergencyOrderDao.incrementRecalculationCount(activeOrderId);
                    refreshAllCachedAgentsSync();
                    if (syncManager != null) {
                        syncManager.syncNowAsync(true);
                    }
                }

                List<GeoPoint> recalculatedRoute = dijkstraRouteEngine.calculateShortestPath(
                        nearestNodeId,
                        destinationNodeId
                );
                deliverSuccess(callback, recalculatedRoute);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public void updateOrderStatus(
            long orderId,
            @NonNull OrderStatus newStatus,
            @Nullable RepositoryCallback<Boolean> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                Long completedAt = (newStatus == OrderStatus.COMPLETED) ? System.currentTimeMillis() : null;
                EmergencyOrder targetOrder = emergencyOrderDao.getOrderByIdSync(orderId);
                int updated = emergencyOrderDao.updateOrderStatus(orderId, newStatus, completedAt);
                if (newStatus == OrderStatus.COMPLETED && targetOrder != null) {
                    emergencyOrderDao.completeActiveOrdersForAgent(
                            targetOrder.getAssignedAgentId(),
                            OrderStatus.COMPLETED,
                            completedAt
                    );
                }
                refreshAllCachedAgentsSync();
                if (syncManager != null) {
                    syncManager.syncNowAsync(true);
                }
                deliverSuccess(callback, updated > 0);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }
}
