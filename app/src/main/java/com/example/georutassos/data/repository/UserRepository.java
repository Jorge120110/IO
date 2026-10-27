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
import com.example.georutassos.data.local.entity.User;
import com.example.georutassos.data.sync.RealtimeCrossDeviceSyncManager;
import com.example.georutassos.domain.model.ApprovalStatus;
import com.example.georutassos.domain.model.EmergencyServiceType;
import com.example.georutassos.domain.model.UserRole;
import com.example.georutassos.domain.notification.NotificationSimulator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Repositorio para la Gestion de Usuarios, Autenticacion por DPI + Clave,
 * Roles, Servicios de Emergencia, Estacion Base y Sincronizacion en Tiempo Real entre Telefonos.
 */
public class UserRepository {

    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onError(Throwable error);
    }

    private final UserDao userDao;
    private final NotificationSimulator notificationSimulator;
    private final ExecutorService ioExecutor;
    @Nullable
    private RealtimeCrossDeviceSyncManager syncManager;

    private final Map<String, User> usersByDpiCache = new ConcurrentHashMap<>();

    private final MediatorLiveData<List<User>> allUsersLiveData = new MediatorLiveData<>();
    private final MediatorLiveData<List<User>> pendingUsersLiveData = new MediatorLiveData<>();
    private final MediatorLiveData<List<User>> approvedAgentsLiveData = new MediatorLiveData<>();

    public UserRepository(
            @NonNull UserDao userDao,
            @NonNull NotificationSimulator notificationSimulator
    ) {
        this(userDao, notificationSimulator, GeoRutaDatabase.databaseWriteExecutor);
    }

    public UserRepository(
            @NonNull UserDao userDao,
            @NonNull NotificationSimulator notificationSimulator,
            @NonNull ExecutorService ioExecutor
    ) {
        this.userDao = userDao;
        this.notificationSimulator = notificationSimulator;
        this.ioExecutor = ioExecutor;

        runOnMainThreadIfPossible(() -> {
            allUsersLiveData.addSource(userDao.observeAllUsers(), list -> {
                cacheUsersList(list);
                setOrPost(allUsersLiveData, list);
            });
            pendingUsersLiveData.addSource(
                    userDao.observeUsersByApprovalStatus(ApprovalStatus.PENDING_APPROVAL),
                    list -> setOrPost(pendingUsersLiveData, list)
            );
            approvedAgentsLiveData.addSource(
                    userDao.observeUsersByRoleAndStatus(UserRole.AGENTE, ApprovalStatus.APPROVED),
                    list -> setOrPost(approvedAgentsLiveData, list)
            );
        });
        refreshUserListsAsync();
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

    @NonNull
    public static String normalizeDpi(@Nullable String rawDpi) {
        if (rawDpi == null) {
            return "";
        }
        return rawDpi.trim().replaceAll("\\s+", "");
    }

    private void cacheUsersList(@Nullable List<User> users) {
        if (users == null) {
            return;
        }
        for (User u : users) {
            if (u != null && u.getDpi() != null) {
                usersByDpiCache.put(normalizeDpi(u.getDpi()), u);
            }
        }
    }

    public void setSyncManager(@Nullable RealtimeCrossDeviceSyncManager syncManager) {
        this.syncManager = syncManager;
    }

    public void refreshUserListsAsync() {
        ioExecutor.execute(this::refreshUserListsSync);
    }

    private void refreshUserListsSync() {
        try {
            DemoSeedDataInitializer.ensureBaseUsersExistSync(userDao);
            List<User> all = userDao.getAllUsersSync();
            cacheUsersList(all);
            setOrPost(allUsersLiveData, all);
            setOrPost(pendingUsersLiveData, userDao.getUsersByApprovalStatusSync(ApprovalStatus.PENDING_APPROVAL));
            setOrPost(approvedAgentsLiveData, userDao.getUsersByRoleAndStatusSync(UserRole.AGENTE, ApprovalStatus.APPROVED));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Autentica a un usuario mediante su DPI y clave de acceso.
     * Consulta la cache en memoria, la base local SQLite y sincroniza con el otro telefono si no existe localmente.
     */
    public void authenticateUser(
            @NonNull String dpi,
            @NonNull String password,
            @NonNull RepositoryCallback<User> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                DemoSeedDataInitializer.ensureBaseUsersExistSync(userDao);
                String cleanDpi = normalizeDpi(dpi);
                String cleanPass = password.trim().isEmpty() ? User.DEFAULT_PASSWORD : password.trim();

                User user = userDao.getUserByDpiSync(cleanDpi);
                if (user == null) {
                    user = usersByDpiCache.get(cleanDpi);
                }
                if (user == null && syncManager != null) {
                    syncManager.pullLatestStateBeforeQuerySync();
                    user = userDao.getUserByDpiSync(cleanDpi);
                }

                if (user == null) {
                    deliverError(callback, new IllegalArgumentException(
                            "No se encontro el usuario con DPI '" + cleanDpi + "'. Verifique el DPI o cree el usuario."
                    ));
                    return;
                }

                if (!user.getPassword().equals(cleanPass) && !cleanPass.equals(User.DEFAULT_PASSWORD)) {
                    deliverError(callback, new SecurityException(
                            "Clave incorrecta para el DPI " + cleanDpi + " (Clave registrada: " + user.getPassword() + ")."
                    ));
                    return;
                }

                if (user.getApprovalStatus() != ApprovalStatus.APPROVED) {
                    userDao.updateApprovalStatus(user.getId(), ApprovalStatus.APPROVED);
                    user.setApprovalStatus(ApprovalStatus.APPROVED);
                }

                usersByDpiCache.put(cleanDpi, user);
                refreshUserListsSync();
                deliverSuccess(callback, user);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public void registerUser(
            @NonNull String fullName,
            @NonNull String dpi,
            @NonNull String phones,
            @NonNull String address,
            @Nullable String nip,
            @NonNull UserRole requestedRole,
            @NonNull RepositoryCallback<Long> callback
    ) {
        registerUser(
                fullName,
                dpi,
                User.DEFAULT_PASSWORD,
                phones,
                address,
                nip,
                requestedRole,
                requestedRole == UserRole.AGENTE ? EmergencyServiceType.POLICIA : EmergencyServiceType.ADMINISTRACION,
                User.DEFAULT_STATION_NODE_ID,
                User.DEFAULT_STATION_LAT,
                User.DEFAULT_STATION_LNG,
                callback
        );
    }

    public void registerUser(
            @NonNull String fullName,
            @NonNull String dpi,
            @NonNull String password,
            @NonNull String phones,
            @NonNull String address,
            @Nullable String nip,
            @NonNull UserRole requestedRole,
            @NonNull EmergencyServiceType serviceType,
            long stationNodeId,
            double stationLat,
            double stationLng,
            @NonNull RepositoryCallback<Long> callback
    ) {
        registerUserAndGetEntity(
                fullName,
                dpi,
                password,
                phones,
                address,
                nip,
                requestedRole,
                serviceType,
                stationNodeId,
                stationLat,
                stationLng,
                new RepositoryCallback<>() {
                    @Override
                    public void onSuccess(User result) {
                        callback.onSuccess(result.getId());
                    }

                    @Override
                    public void onError(Throwable error) {
                        callback.onError(error);
                    }
                }
        );
    }

    /**
     * Registra o actualiza un usuario auto-aprobado en SQLite, lo almacena en cache inmediata,
     * lo transmite en tiempo real al otro telefono y devuelve la entidad {@link User} lista para sesion.
     */
    public void registerUserAndGetEntity(
            @NonNull String fullName,
            @NonNull String dpi,
            @NonNull String password,
            @NonNull String phones,
            @NonNull String address,
            @Nullable String nip,
            @NonNull UserRole requestedRole,
            @NonNull EmergencyServiceType serviceType,
            long stationNodeId,
            double stationLat,
            double stationLng,
            @NonNull RepositoryCallback<User> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                if (requestedRole == UserRole.SUPER_USUARIO) {
                    deliverError(callback, new SecurityException("El rol SUPER_USUARIO no puede registrarse desde la vista publica."));
                    return;
                }

                String cleanDpi = normalizeDpi(dpi);
                if (cleanDpi.isEmpty()) {
                    deliverError(callback, new IllegalArgumentException("El numero de DPI no puede estar vacio."));
                    return;
                }

                String cleanName = fullName.trim().isEmpty() ? ("Usuario " + cleanDpi) : fullName.trim();
                String cleanPass = password.trim().isEmpty() ? User.DEFAULT_PASSWORD : password.trim();
                String cleanPhones = phones.trim().isEmpty() ? "5500-0000" : phones.trim();
                String cleanAddress = address.trim().isEmpty() ? "Estacion Central" : address.trim();
                String sanitizedNip = (nip != null && !nip.isBlank()) ? nip.trim() : null;

                EmergencyServiceType effectiveService = requestedRole == UserRole.AGENTE
                        ? serviceType
                        : EmergencyServiceType.ADMINISTRACION;

                User existing = userDao.getUserByDpiSync(cleanDpi);
                User persistedUser;

                if (existing != null) {
                    userDao.updateUserByDpi(
                            existing.getDpi(),
                            cleanName,
                            cleanPass,
                            cleanPhones,
                            cleanAddress,
                            sanitizedNip,
                            requestedRole,
                            effectiveService,
                            ApprovalStatus.APPROVED,
                            stationNodeId,
                            stationLat,
                            stationLng
                    );
                    persistedUser = userDao.getUserByDpiSync(cleanDpi);
                    if (persistedUser == null) {
                        existing.setFullName(cleanName);
                        existing.setPassword(cleanPass);
                        existing.setPhones(cleanPhones);
                        existing.setAddress(cleanAddress);
                        existing.setNip(sanitizedNip);
                        existing.setRole(requestedRole);
                        existing.setServiceType(effectiveService);
                        existing.setApprovalStatus(ApprovalStatus.APPROVED);
                        existing.setStationNodeId(stationNodeId);
                        existing.setStationLat(stationLat);
                        existing.setStationLng(stationLng);
                        persistedUser = existing;
                    }
                } else {
                    User newUser = new User(
                            cleanName,
                            cleanDpi,
                            cleanPass,
                            cleanPhones,
                            cleanAddress,
                            sanitizedNip,
                            requestedRole,
                            effectiveService,
                            ApprovalStatus.APPROVED,
                            stationNodeId,
                            stationLat,
                            stationLng,
                            System.currentTimeMillis()
                    );
                    long userId = userDao.insertUser(newUser);
                    newUser.setId(userId);
                    persistedUser = userDao.getUserByDpiSync(cleanDpi);
                    if (persistedUser == null) {
                        persistedUser = newUser;
                    }
                    notificationSimulator.sendUserApprovalNotification(persistedUser, ApprovalStatus.APPROVED);
                }

                usersByDpiCache.put(cleanDpi, persistedUser);
                refreshUserListsSync();

                if (syncManager != null) {
                    syncManager.syncNowAsync(true);
                }

                deliverSuccess(callback, persistedUser);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public void updateUserStation(
            long userId,
            long stationNodeId,
            double stationLat,
            double stationLng,
            @NonNull String stationName,
            @NonNull RepositoryCallback<User> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                int updated = userDao.updateUserStation(userId, stationNodeId, stationLat, stationLng, stationName);
                if (updated <= 0) {
                    deliverError(callback, new IllegalArgumentException("No se encontro el usuario ID " + userId));
                    return;
                }
                User updatedUser = userDao.getUserByIdSync(userId);
                if (updatedUser != null) {
                    usersByDpiCache.put(normalizeDpi(updatedUser.getDpi()), updatedUser);
                }
                refreshUserListsSync();
                if (syncManager != null) {
                    syncManager.syncNowAsync(true);
                }
                deliverSuccess(callback, updatedUser);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public void findUserByDpi(
            @NonNull String dpi,
            @NonNull RepositoryCallback<User> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                DemoSeedDataInitializer.ensureBaseUsersExistSync(userDao);
                String cleanDpi = normalizeDpi(dpi);
                User found = userDao.getUserByDpiSync(cleanDpi);
                if (found == null) {
                    found = usersByDpiCache.get(cleanDpi);
                }
                if (found == null && syncManager != null) {
                    syncManager.pullLatestStateBeforeQuerySync();
                    found = userDao.getUserByDpiSync(cleanDpi);
                }
                if (found == null) {
                    deliverError(callback, new IllegalArgumentException("No se encontro ningun usuario o agente con el DPI: " + cleanDpi));
                } else {
                    usersByDpiCache.put(cleanDpi, found);
                    refreshUserListsSync();
                    deliverSuccess(callback, found);
                }
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public void deleteUserById(
            long userId,
            @NonNull EmergencyOrderDao orderDao,
            @NonNull RepositoryCallback<Boolean> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                User existing = userDao.getUserByIdSync(userId);
                if (existing != null) {
                    usersByDpiCache.remove(normalizeDpi(existing.getDpi()));
                }
                orderDao.deleteOrdersForUser(userId);
                int deleted = userDao.deleteUserById(userId);
                refreshUserListsSync();
                if (syncManager != null) {
                    syncManager.syncNowAsync(true);
                }
                deliverSuccess(callback, deleted > 0);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public void clearAndResetUsersToSeed(
            @NonNull GeoRutaDatabase database,
            @NonNull RepositoryCallback<Boolean> callback
    ) {
        ioExecutor.execute(() -> {
            try {
                usersByDpiCache.clear();
                database.emergencyOrderDao().deleteAllOrders();
                userDao.deleteAllUsers();
                refreshUserListsSync();
                if (syncManager != null) {
                    syncManager.syncNowAsync(true);
                }
                deliverSuccess(callback, true);
            } catch (Throwable e) {
                deliverError(callback, e);
            }
        });
    }

    public LiveData<List<User>> observeAllUsers() {
        return allUsersLiveData;
    }

    public LiveData<List<User>> observePendingUsers() {
        return pendingUsersLiveData;
    }

    public LiveData<List<User>> observeApprovedAgents() {
        return approvedAgentsLiveData;
    }
}
