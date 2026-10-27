package com.example.georutassos.data.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.georutassos.data.local.dao.EmergencyOrderDao;
import com.example.georutassos.data.local.dao.UserDao;
import com.example.georutassos.data.local.database.DemoSeedDataInitializer;
import com.example.georutassos.data.local.database.GeoRutaDatabase;
import com.example.georutassos.data.local.entity.EmergencyOrder;
import com.example.georutassos.data.local.entity.User;
import com.example.georutassos.domain.model.ApprovalStatus;
import com.example.georutassos.domain.model.EmergencyServiceType;
import com.example.georutassos.domain.model.OrderStatus;
import com.example.georutassos.domain.model.UserRole;
import com.example.georutassos.domain.notification.NotificationSimulator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Motor de Sincronizacion en Tiempo Real entre Multiples Dispositivos (Telefonos Admin y Agente).
 * <p>
 * Combina dos canales simultaneos de baja latencia:
 * 1) Stream Persistente HTTPS en la Nube (sin rate-limit 429): Mantiene una conexion abierta
 *    que recibe al instante (< 200ms) cada usuario creado o emergencia despachada.
 * 2) Canal P2P Red Local Wi-Fi / Hotspot (HTTP Puerto 45891 + UDP 45890 + Escaneo LAN):
 *    Sincroniza directamente entre telefonos en la misma red Wi-Fi o Hotspot.
 */
public final class RealtimeCrossDeviceSyncManager {

    public interface OnSyncDataMergedListener {
        void onDataMerged(boolean usersChanged, boolean ordersChanged);
    }

    private static final int LOCAL_HTTP_PORT = 45891;
    private static final int PC_WEB_HTTP_PORT = 8080;
    private static final int USB_ADB_REVERSE_PORT = 45892;
    private static final int LOCAL_UDP_PORT = 45890;
    private static final String CLOUD_RELAY_TOPIC_URL = "https://ntfy.sh/georutas_sos_gt_clean_v5";
    private static final String UDP_BEACON_PREFIX = "GEORUTA_SOS_PEER:";

    private static volatile RealtimeCrossDeviceSyncManager INSTANCE;

    private final GeoRutaDatabase database;
    private final UserDao userDao;
    private final EmergencyOrderDao orderDao;
    private final NotificationSimulator notificationSimulator;

    private final ScheduledExecutorService syncScheduler = Executors.newScheduledThreadPool(3);
    private final ExecutorService networkIoPool = Executors.newCachedThreadPool();
    private final ExecutorService subnetScanPool = Executors.newFixedThreadPool(16);

    private final Set<String> discoveredPeerIps = ConcurrentHashMap.newKeySet();
    private final Set<String> processedCloudMessageIds = ConcurrentHashMap.newKeySet();
    private final Set<OnSyncDataMergedListener> listeners = ConcurrentHashMap.newKeySet();

    private final MutableLiveData<String> syncStatusLiveData = new MutableLiveData<>(
            "Sincronizacion Tiempo Real: Iniciando..."
    );

    private volatile boolean isRunning = false;
    private volatile boolean cloudStreamConnected = false;
    private volatile String lastPublishedPayloadHash = "";
    private volatile long lastCloudPollTimestampMs = 0L;

    private RealtimeCrossDeviceSyncManager(
            @NonNull GeoRutaDatabase database,
            @NonNull NotificationSimulator notificationSimulator
    ) {
        this.database = database;
        this.userDao = database.userDao();
        this.orderDao = database.emergencyOrderDao();
        this.notificationSimulator = notificationSimulator;
    }

    public static RealtimeCrossDeviceSyncManager getInstance(
            @NonNull GeoRutaDatabase database,
            @NonNull NotificationSimulator notificationSimulator
    ) {
        if (INSTANCE == null) {
            synchronized (RealtimeCrossDeviceSyncManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new RealtimeCrossDeviceSyncManager(database, notificationSimulator);
                }
            }
        }
        return INSTANCE;
    }

    public void addSyncListener(@NonNull OnSyncDataMergedListener listener) {
        listeners.add(listener);
    }

    public LiveData<String> getSyncStatusLiveData() {
        return syncStatusLiveData;
    }

    public void addManualPeerIp(@Nullable String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }
        String cleanIp = ipAddress.trim();
        String myIp = getLocalWifiIpv4Address();
        if (!cleanIp.equals(myIp) && !cleanIp.equals("127.0.0.1")) {
            discoveredPeerIps.add(cleanIp);
            syncNowAsync(true);
        }
    }

    public synchronized void startContinuousSync() {
        if (isRunning) {
            return;
        }
        isRunning = true;

        networkIoPool.execute(() -> {
            database.ensureOpenedSync();
            DemoSeedDataInitializer.ensureBaseUsersExistSync(userDao);
            notifyListeners(true, true);
        });

        startLocalHttpSyncServer();
        startLocalUdpDiscoveryListener();
        startPersistentCloudStreamListener();

        // Ciclo rapido cada 2 segundos para P2P local y publicacion de cambios pendientes
        syncScheduler.scheduleWithFixedDelay(() -> {
            try {
                broadcastUdpPresence();
                pullAndPushSyncCycle(false);
            } catch (Throwable ignored) {
            }
        }, 400L, 2000L, TimeUnit.MILLISECONDS);

        // Escaneo automatico de subred local Wi-Fi cada 10 segundos
        syncScheduler.scheduleWithFixedDelay(this::scanLocalSubnetForPeers, 600L, 10000L, TimeUnit.MILLISECONDS);
    }

    /**
     * Dispara una sincronizacion inmediata cuando se crea un usuario o se despacha una orden.
     * Primero empuja (PUSH) el nuevo estado inmediatamente para que el otro telefono lo reciba al instante.
     */
    public void syncNowAsync(boolean forcePush) {
        networkIoPool.execute(() -> {
            if (forcePush) {
                pushLocalStateImmediatelySync();
            }
            pullAndPushSyncCycle(forcePush);
        });
    }

    /**
     * Ejecuta un pull sincrono rapido antes de autenticar o buscar por DPI cuando un usuario
     * fue creado recientemente en el otro telefono.
     */
    public void pullLatestStateBeforeQuerySync() {
        try {
            database.ensureOpenedSync();
            DemoSeedDataInitializer.ensureBaseUsersExistSync(userDao);
            pullFromLocalPeersSync();
            long now = System.currentTimeMillis();
            if (now - lastCloudPollTimestampMs > 2500L) {
                lastCloudPollTimestampMs = now;
                pullFromCloudRelayOnceSync();
            }
        } catch (Throwable ignored) {
        }
    }

    private void pushLocalStateImmediatelySync() {
        try {
            String currentPayload = buildLocalStateJsonString();
            String currentHash = Integer.toHexString(currentPayload.hashCode());
            boolean peerOk = pushToLocalPeersSync(currentPayload);
            boolean cloudOk = pushToCloudRelaySync(currentPayload);
            if (peerOk || cloudOk) {
                lastPublishedPayloadHash = currentHash;
            }
            updateStatusBanner();
        } catch (Throwable ignored) {
        }
    }

    private void pullAndPushSyncCycle(boolean forcePush) {
        database.ensureOpenedSync();
        DemoSeedDataInitializer.ensureBaseUsersExistSync(userDao);

        boolean changedFromPeers = pullFromLocalPeersSync();
        boolean changedFromCloud = false;

        long now = System.currentTimeMillis();
        if (!cloudStreamConnected && (now - lastCloudPollTimestampMs > 8000L)) {
            lastCloudPollTimestampMs = now;
            changedFromCloud = pullFromCloudRelayOnceSync();
        }

        String currentPayload = buildLocalStateJsonString();
        String currentHash = Integer.toHexString(currentPayload.hashCode());

        if (forcePush || !currentHash.equals(lastPublishedPayloadHash)) {
            boolean peerOk = pushToLocalPeersSync(currentPayload);
            boolean cloudOk = pushToCloudRelaySync(currentPayload);
            if (peerOk || cloudOk) {
                lastPublishedPayloadHash = currentHash;
            }
        }

        if (changedFromPeers || changedFromCloud || forcePush) {
            notifyListeners(true, true);
        }

        updateStatusBanner();
    }

    private void updateStatusBanner() {
        String myIp = getLocalWifiIpv4Address();
        String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        int peerCount = discoveredPeerIps.size();
        String peerInfo;
        if (peerCount > 0) {
            peerInfo = "P2P (" + String.join(", ", discoveredPeerIps) + ")";
        } else if (cloudStreamConnected) {
            peerInfo = "Stream Nube Tiempo Real Activo";
        } else {
            peerInfo = "Wi-Fi + Nube Activo";
        }
        syncStatusLiveData.postValue(String.format(
                Locale.US,
                "Sincronizacion Activa | Mi IP: %s | %s | %s",
                myIp != null ? myIp : "Red Movil",
                peerInfo,
                timeStr
        ));
    }

    private void notifyListeners(boolean usersChanged, boolean ordersChanged) {
        for (OnSyncDataMergedListener listener : listeners) {
            try {
                listener.onDataMerged(usersChanged, ordersChanged);
            } catch (Throwable ignored) {
            }
        }
    }

    // =========================================================================================
    // SERIALIZACION COMPACTA Y FUSION (MERGE) DE USUARIOS Y ORDENES DE EMERGENCIA
    // =========================================================================================

    @NonNull
    private String buildLocalStateJsonString() {
        try {
            database.ensureOpenedSync();
            JSONObject root = new JSONObject();
            root.put("schema", 3);
            root.put("timestamp", System.currentTimeMillis());

            List<User> allUsers = userDao.getAllUsersSync();
            Map<Long, String> userIdToDpi = new HashMap<>();
            JSONArray usersArray = new JSONArray();

            for (User u : allUsers) {
                userIdToDpi.put(u.getId(), u.getDpi());
                JSONObject uObj = new JSONObject();
                uObj.put("id", u.getId());
                uObj.put("fullName", u.getFullName());
                uObj.put("dpi", u.getDpi());
                uObj.put("password", u.getPassword());
                uObj.put("phones", u.getPhones());
                uObj.put("address", u.getAddress());
                uObj.put("nip", u.getNip() != null ? u.getNip() : "");
                uObj.put("role", u.getRole().name());
                uObj.put("serviceType", u.getServiceType().name());
                uObj.put("approvalStatus", u.getApprovalStatus().name());
                uObj.put("stationNodeId", u.getStationNodeId());
                uObj.put("stationLat", u.getStationLat());
                uObj.put("stationLng", u.getStationLng());
                uObj.put("createdAt", u.getCreatedAt());
                usersArray.put(uObj);
            }
            root.put("users", usersArray);

            List<EmergencyOrder> allOrders = orderDao.getAllOrdersSync();
            JSONArray ordersArray = new JSONArray();
            int maxOrdersToSync = Math.min(allOrders.size(), 12);
            for (int i = 0; i < maxOrdersToSync; i++) {
                EmergencyOrder o = allOrders.get(i);
                JSONObject oObj = new JSONObject();
                oObj.put("id", o.getId());
                oObj.put("assignedAgentId", o.getAssignedAgentId());
                oObj.put("assignedAgentDpi", userIdToDpi.getOrDefault(o.getAssignedAgentId(), ""));
                oObj.put("dispatcherAdminId", o.getDispatcherAdminId());
                oObj.put("originNodeId", o.getOriginNodeId());
                oObj.put("originLat", o.getOriginLat());
                oObj.put("originLng", o.getOriginLng());
                oObj.put("destinationNodeId", o.getDestinationNodeId());
                oObj.put("destinationLat", o.getDestinationLat());
                oObj.put("destinationLng", o.getDestinationLng());
                oObj.put("description", o.getDescription() != null ? o.getDescription() : "");
                oObj.put("status", o.getStatus().name());
                oObj.put("assignedAt", o.getAssignedAt());
                if (o.getCompletedAt() != null) {
                    oObj.put("completedAt", o.getCompletedAt());
                }
                oObj.put("recalculationCount", o.getRecalculationCount());
                ordersArray.put(oObj);
            }
            root.put("orders", ordersArray);

            return root.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    private synchronized boolean mergeRemoteStateJson(@Nullable String jsonText) {
        if (jsonText == null || jsonText.isBlank() || !jsonText.trim().startsWith("{")) {
            return false;
        }
        database.ensureOpenedSync();
        boolean anyChange = false;
        try {
            JSONObject root = new JSONObject(jsonText);

            // 1. Fusionar Usuarios por DPI
            JSONArray usersArray = root.optJSONArray("users");
            if (usersArray != null) {
                for (int i = 0; i < usersArray.length(); i++) {
                    JSONObject uObj = usersArray.optJSONObject(i);
                    if (uObj == null) {
                        continue;
                    }
                    String dpi = uObj.optString("dpi", "").trim().replaceAll("\\s+", "");
                    if (dpi.isEmpty()) {
                        continue;
                    }
                    String fullName = uObj.optString("fullName", "Usuario " + dpi);
                    String password = uObj.optString("password", "1234");
                    String phones = uObj.optString("phones", "5500-0000");
                    String address = uObj.optString("address", "Estacion Base");
                    String nipRaw = uObj.optString("nip", "");
                    String nip = nipRaw.isBlank() ? null : nipRaw;

                    UserRole role = parseRoleSafe(uObj.optString("role", "AGENTE"));
                    EmergencyServiceType serviceType = parseServiceSafe(uObj.optString("serviceType", "POLICIA"));
                    ApprovalStatus approvalStatus = parseApprovalSafe(uObj.optString("approvalStatus", "APPROVED"));
                    long stationNodeId = uObj.optLong("stationNodeId", User.DEFAULT_STATION_NODE_ID);
                    double stationLat = uObj.optDouble("stationLat", User.DEFAULT_STATION_LAT);
                    double stationLng = uObj.optDouble("stationLng", User.DEFAULT_STATION_LNG);
                    long createdAt = uObj.optLong("createdAt", System.currentTimeMillis());

                    User existing = userDao.getUserByDpiSync(dpi);
                    if (existing == null) {
                        User newUser = new User(
                                fullName,
                                dpi,
                                password,
                                phones,
                                address,
                                nip,
                                role,
                                serviceType,
                                approvalStatus,
                                stationNodeId,
                                stationLat,
                                stationLng,
                                createdAt
                        );
                        userDao.insertUser(newUser);
                        anyChange = true;
                    } else if (!existing.getFullName().equals(fullName)
                            || !existing.getPassword().equals(password)
                            || existing.getRole() != role
                            || existing.getServiceType() != serviceType
                            || existing.getApprovalStatus() != approvalStatus
                            || Math.abs(existing.getStationLat() - stationLat) > 1e-6
                            || Math.abs(existing.getStationLng() - stationLng) > 1e-6) {
                        userDao.updateUserByDpi(
                                existing.getDpi(),
                                fullName,
                                password,
                                phones,
                                address,
                                nip,
                                role,
                                serviceType,
                                approvalStatus,
                                stationNodeId,
                                stationLat,
                                stationLng
                        );
                        anyChange = true;
                    }
                }
            }

            // 2. Fusionar Ordenes de Emergencia (resolviendo el agente por DPI para que coincida entre telefonos)
            JSONArray ordersArray = root.optJSONArray("orders");
            if (ordersArray != null) {
                for (int i = 0; i < ordersArray.length(); i++) {
                    JSONObject oObj = ordersArray.optJSONObject(i);
                    if (oObj == null) {
                        continue;
                    }
                    long assignedAt = oObj.optLong("assignedAt", 0L);
                    if (assignedAt <= 0L) {
                        continue;
                    }

                    String assignedAgentDpi = oObj.optString("assignedAgentDpi", "").trim().replaceAll("\\s+", "");
                    long resolvedAgentId = oObj.optLong("assignedAgentId", 3L);
                    if (!assignedAgentDpi.isEmpty()) {
                        User localAgent = userDao.getUserByDpiSync(assignedAgentDpi);
                        if (localAgent != null) {
                            resolvedAgentId = localAgent.getId();
                        }
                    }

                    OrderStatus incomingStatus = parseOrderStatusSafe(oObj.optString("status", "ASSIGNED"));
                    Long completedAt = oObj.has("completedAt") ? oObj.optLong("completedAt") : null;
                    int recalcCount = oObj.optInt("recalculationCount", 0);

                    EmergencyOrder existingOrder = orderDao.getOrderByAssignedAtSync(assignedAt);
                    if (existingOrder == null) {
                        EmergencyOrder newOrder = new EmergencyOrder(
                                resolvedAgentId,
                                oObj.optLong("dispatcherAdminId", 2L),
                                oObj.optLong("originNodeId", User.DEFAULT_STATION_NODE_ID),
                                oObj.optDouble("originLat", User.DEFAULT_STATION_LAT),
                                oObj.optDouble("originLng", User.DEFAULT_STATION_LNG),
                                oObj.optLong("destinationNodeId", 133313L),
                                oObj.optDouble("destinationLat", 14.6195),
                                oObj.optDouble("destinationLng", -90.5153),
                                oObj.optString("description", "Emergencia SOS"),
                                incomingStatus,
                                assignedAt,
                                completedAt,
                                recalcCount
                        );
                        long insertedId = orderDao.insertOrder(newOrder);
                        newOrder.setId(insertedId);
                        notificationSimulator.sendEmergencyDispatchAlert(newOrder);
                        anyChange = true;
                    } else {
                        boolean statusAdvanced = statusPriority(incomingStatus) > statusPriority(existingOrder.getStatus());
                        boolean recalcIncreased = recalcCount > existingOrder.getRecalculationCount();
                        if (statusAdvanced || recalcIncreased) {
                            OrderStatus mergedStatus = statusAdvanced ? incomingStatus : existingOrder.getStatus();
                            Long mergedCompleted = completedAt != null ? completedAt : existingOrder.getCompletedAt();
                            int mergedRecalc = Math.max(recalcCount, existingOrder.getRecalculationCount());
                            orderDao.updateOrderSyncState(
                                    existingOrder.getId(),
                                    mergedStatus,
                                    mergedCompleted,
                                    mergedRecalc
                            );
                            anyChange = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return anyChange;
    }

    private int statusPriority(@NonNull OrderStatus status) {
        return switch (status) {
            case ASSIGNED -> 1;
            case IN_PROGRESS -> 2;
            case COMPLETED, CANCELLED -> 3;
        };
    }

    private UserRole parseRoleSafe(String val) {
        try {
            return UserRole.valueOf(val.trim());
        } catch (Throwable e) {
            return UserRole.AGENTE;
        }
    }

    private EmergencyServiceType parseServiceSafe(String val) {
        try {
            return EmergencyServiceType.valueOf(val.trim());
        } catch (Throwable e) {
            return EmergencyServiceType.POLICIA;
        }
    }

    private ApprovalStatus parseApprovalSafe(String val) {
        try {
            return ApprovalStatus.valueOf(val.trim());
        } catch (Throwable e) {
            return ApprovalStatus.APPROVED;
        }
    }

    private OrderStatus parseOrderStatusSafe(String val) {
        try {
            return OrderStatus.valueOf(val.trim());
        } catch (Throwable e) {
            return OrderStatus.ASSIGNED;
        }
    }

    // =========================================================================================
    // CANAL 1: STREAM PERSISTENTE EN LA NUBE HTTPS (SIN RATE-LIMIT 429, LATENCIA < 200ms)
    // =========================================================================================

    private void startPersistentCloudStreamListener() {
        networkIoPool.execute(() -> {
            while (isRunning) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(CLOUD_RELAY_TOPIC_URL + "/json?since=20m");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(65000);
                    if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        cloudStreamConnected = true;
                        updateStatusBanner();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while (isRunning && (line = reader.readLine()) != null) {
                                if (line.isBlank()) {
                                    continue;
                                }
                                JSONObject wrapper = new JSONObject(line);
                                String msgId = wrapper.optString("id", "");
                                String eventType = wrapper.optString("event", "");
                                if (!"message".equals(eventType) || msgId.isEmpty()) {
                                    continue;
                                }
                                if (processedCloudMessageIds.add(msgId)) {
                                    String embeddedJson = wrapper.optString("message", "");
                                    if (mergeRemoteStateJson(embeddedJson)) {
                                        notifyListeners(true, true);
                                        updateStatusBanner();
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    cloudStreamConnected = false;
                    if (conn != null) {
                        conn.disconnect();
                    }
                }

                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private boolean pushToCloudRelaySync(@NonNull String stateJson) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CLOUD_RELAY_TOPIC_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            byte[] bytes = stateJson.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private boolean pullFromCloudRelayOnceSync() {
        HttpURLConnection conn = null;
        boolean changed = false;
        try {
            URL url = new URL(CLOUD_RELAY_TOPIC_URL + "/json?poll=1&since=20m");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        JSONObject wrapper = new JSONObject(line);
                        String msgId = wrapper.optString("id", "");
                        String eventType = wrapper.optString("event", "");
                        if (!"message".equals(eventType) || msgId.isEmpty()) {
                            continue;
                        }
                        if (processedCloudMessageIds.add(msgId)) {
                            String embeddedJson = wrapper.optString("message", "");
                            if (mergeRemoteStateJson(embeddedJson)) {
                                changed = true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return changed;
    }

    // =========================================================================================
    // CANAL 2: P2P EN RED LOCAL WI-FI / HOTSPOT (SERVIDOR HTTP 45891 + UDP 45890)
    // =========================================================================================

    private void startLocalHttpSyncServer() {
        networkIoPool.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket()) {
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(LOCAL_HTTP_PORT));
                while (isRunning) {
                    Socket client = serverSocket.accept();
                    networkIoPool.execute(() -> handleLocalPeerHttpConnection(client));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private void handleLocalPeerHttpConnection(@NonNull Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(2500);
            InetAddress remoteAddr = socket.getInetAddress();
            if (remoteAddr != null) {
                String remoteIp = remoteAddr.getHostAddress();
                String myIp = getLocalWifiIpv4Address();
                if (remoteIp != null && !remoteIp.equals(myIp) && !remoteIp.equals("127.0.0.1")) {
                    discoveredPeerIps.add(remoteIp);
                }
            }

            InputStream is = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }

            int contentLength = 0;
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                String lower = headerLine.toLowerCase(Locale.US);
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring(15).trim());
                }
            }

            if (requestLine.startsWith("POST") && contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int r = reader.read(bodyChars, totalRead, contentLength - totalRead);
                    if (r < 0) {
                        break;
                    }
                    totalRead += r;
                }
                String incomingJson = new String(bodyChars, 0, totalRead);
                if (mergeRemoteStateJson(incomingJson)) {
                    notifyListeners(true, true);
                }
            }

            String responseJson = buildLocalStateJsonString();
            byte[] bodyBytes = responseJson.getBytes(StandardCharsets.UTF_8);
            String httpHeader = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";

            OutputStream os = socket.getOutputStream();
            os.write(httpHeader.getBytes(StandardCharsets.UTF_8));
            os.write(bodyBytes);
            os.flush();
        } catch (Throwable ignored) {
        }
    }

    private void startLocalUdpDiscoveryListener() {
        networkIoPool.execute(() -> {
            try (DatagramSocket udpSocket = new DatagramSocket(null)) {
                udpSocket.setReuseAddress(true);
                udpSocket.bind(new InetSocketAddress(LOCAL_UDP_PORT));
                byte[] buf = new byte[256];
                while (isRunning) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith(UDP_BEACON_PREFIX)) {
                        String peerIp = packet.getAddress().getHostAddress();
                        String myIp = getLocalWifiIpv4Address();
                        if (peerIp != null && !peerIp.equals(myIp) && !peerIp.equals("127.0.0.1")) {
                            discoveredPeerIps.add(peerIp);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private void broadcastUdpPresence() {
        String myIp = getLocalWifiIpv4Address();
        if (myIp == null) {
            return;
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] payload = (UDP_BEACON_PREFIX + myIp).getBytes(StandardCharsets.UTF_8);
            DatagramPacket globalPacket = new DatagramPacket(
                    payload,
                    payload.length,
                    InetAddress.getByName("255.255.255.255"),
                    LOCAL_UDP_PORT
            );
            socket.send(globalPacket);

            int lastDot = myIp.lastIndexOf('.');
            if (lastDot > 0) {
                String subnetBroadcast = myIp.substring(0, lastDot + 1) + "255";
                DatagramPacket subnetPacket = new DatagramPacket(
                        payload,
                        payload.length,
                        InetAddress.getByName(subnetBroadcast),
                        LOCAL_UDP_PORT
                );
                socket.send(subnetPacket);
            }
        } catch (Throwable ignored) {
        }
    }

    private void scanLocalSubnetForPeers() {
        String myIp = getLocalWifiIpv4Address();
        if (myIp == null) {
            return;
        }
        int lastDot = myIp.lastIndexOf('.');
        if (lastDot <= 0) {
            return;
        }
        String prefix = myIp.substring(0, lastDot + 1);
        for (int host = 1; host <= 254; host++) {
            final String candidateIp = prefix + host;
            if (candidateIp.equals(myIp)) {
                continue;
            }
            subnetScanPool.execute(() -> {
                try (Socket probe = new Socket()) {
                    probe.connect(new InetSocketAddress(candidateIp, LOCAL_HTTP_PORT), 350);
                    discoveredPeerIps.add(candidateIp);
                    return;
                } catch (Throwable ignored) {
                }
                try (Socket probeWeb = new Socket()) {
                    probeWeb.connect(new InetSocketAddress(candidateIp, PC_WEB_HTTP_PORT), 350);
                    discoveredPeerIps.add(candidateIp);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private List<String> buildSyncEndpointUrls() {
        java.util.ArrayList<String> urls = new java.util.ArrayList<>();
        // Puente USB ADB reverse hacia el servidor Web de la PC (adb reverse tcp:45892 tcp:8080)
        urls.add("http://127.0.0.1:" + USB_ADB_REVERSE_PORT + "/sync");
        // Puente Emulador Android hacia PC host
        urls.add("http://10.0.2.2:" + PC_WEB_HTTP_PORT + "/sync");
        for (String peerIp : discoveredPeerIps) {
            if (peerIp.contains(":")) {
                urls.add("http://" + peerIp + "/sync");
            } else {
                urls.add("http://" + peerIp + ":" + LOCAL_HTTP_PORT + "/sync");
                urls.add("http://" + peerIp + ":" + PC_WEB_HTTP_PORT + "/sync");
            }
        }
        return urls;
    }

    private boolean pullFromLocalPeersSync() {
        boolean anyChanged = false;
        for (String endpointUrl : buildSyncEndpointUrls()) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(endpointUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(700);
                conn.setReadTimeout(1200);
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    if (mergeRemoteStateJson(sb.toString())) {
                        anyChanged = true;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return anyChanged;
    }

    private boolean pushToLocalPeersSync(@NonNull String stateJson) {
        boolean anySuccess = false;
        byte[] bytes = stateJson.getBytes(StandardCharsets.UTF_8);
        for (String endpointUrl : buildSyncEndpointUrls()) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(endpointUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(700);
                conn.setReadTimeout(1200);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    anySuccess = true;
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return anySuccess;
    }

    @Nullable
    public static String getLocalWifiIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
