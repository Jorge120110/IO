package com.example.georutassos.presentation.registration;

import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.georutassos.data.local.dao.EmergencyOrderDao;
import com.example.georutassos.data.local.database.GeoRutaDatabase;
import com.example.georutassos.data.local.entity.User;
import com.example.georutassos.data.repository.UserRepository;
import com.example.georutassos.domain.model.ApprovalStatus;
import com.example.georutassos.domain.model.EmergencyServiceType;
import com.example.georutassos.domain.model.UserRole;

import java.util.List;
import java.util.Locale;

/**
 * ViewModel para el Modulo 1: Creacion de Usuario (con auto-aprobacion y auto-inicio de sesion),
 * Inicio de Sesion mediante DPI y Clave, y Seleccion Dinamica de Estacion Cercana.
 */
public class RegistrationViewModel extends ViewModel {

    public record RegistrationUiState(
            boolean isLoading,
            @Nullable Long registeredUserId,
            @Nullable ApprovalStatus lastSavedStatus,
            @Nullable String statusMessage,
            @Nullable String errorMessage
    ) {
        public static RegistrationUiState idle() {
            return new RegistrationUiState(false, null, null, null, null);
        }
    }

    private final UserRepository userRepository;
    private final MutableLiveData<RegistrationUiState> uiState =
            new MutableLiveData<>(RegistrationUiState.idle());
    private final MutableLiveData<User> authenticatedUser = new MutableLiveData<>(null);
    private final MutableLiveData<String> authError = new MutableLiveData<>(null);

    public RegistrationViewModel(@NonNull UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private static <T> void setOrPost(@NonNull MutableLiveData<T> liveData, @Nullable T value) {
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null && Looper.myLooper() == mainLooper) {
            liveData.setValue(value);
        } else {
            liveData.postValue(value);
        }
    }

    public LiveData<RegistrationUiState> getUiState() {
        return uiState;
    }

    public LiveData<User> getAuthenticatedUser() {
        return authenticatedUser;
    }

    public LiveData<String> getAuthError() {
        return authError;
    }

    public LiveData<List<User>> observeAllUsers() {
        return userRepository.observeAllUsers();
    }

    public LiveData<List<User>> observePendingApprovals() {
        return userRepository.observePendingUsers();
    }

    public void refreshUsers() {
        userRepository.refreshUserListsAsync();
    }

    /**
     * Inicia sesion con el DPI y la clave del usuario aprobado.
     */
    public void loginWithDpiAndPassword(@Nullable String dpi, @Nullable String password) {
        String cleanDpi = UserRepository.normalizeDpi(dpi);
        if (cleanDpi.isEmpty()) {
            setOrPost(authError, "Ingrese su numero de DPI para iniciar sesion.");
            return;
        }
        String effectivePassword = (password == null || password.trim().isEmpty())
                ? User.DEFAULT_PASSWORD
                : password.trim();

        setOrPost(authError, null);
        userRepository.authenticateUser(cleanDpi, effectivePassword, new UserRepository.RepositoryCallback<>() {
            @Override
            public void onSuccess(User user) {
                setOrPost(authError, null);
                setOrPost(authenticatedUser, user);
            }

            @Override
            public void onError(Throwable error) {
                setOrPost(authError, error.getMessage());
            }
        });
    }

    public void switchActiveSessionUser(@Nullable User user) {
        setOrPost(authError, null);
        setOrPost(authenticatedUser, user);
    }

    public void logout() {
        setOrPost(authError, null);
        setOrPost(authenticatedUser, null);
    }

    public void submitRegistrationForm(
            @Nullable String fullName,
            @Nullable String dpi,
            @Nullable String password,
            @Nullable String phones,
            @Nullable String address,
            @Nullable String nip,
            @Nullable UserRole role,
            @NonNull EmergencyServiceType serviceType,
            long stationNodeId,
            double stationLat,
            double stationLng
    ) {
        String cleanDpi = UserRepository.normalizeDpi(dpi);
        if (cleanDpi.isEmpty()) {
            postError("El numero de DPI es obligatorio para crear el usuario.");
            return;
        }
        if (role == null || role == UserRole.SUPER_USUARIO) {
            postError("Seleccione un rol valido (AGENTE o ADMINISTRADOR).");
            return;
        }

        String cleanName = (fullName == null || fullName.isBlank()) ? ("Usuario " + cleanDpi) : fullName.trim();
        String cleanPassword = (password == null || password.isBlank()) ? User.DEFAULT_PASSWORD : password.trim();
        String cleanPhones = (phones == null || phones.isBlank()) ? "5500-0000" : phones.trim();
        String cleanAddress = (address == null || address.isBlank()) ? "Estacion Central" : address.trim();

        setOrPost(uiState, new RegistrationUiState(true, null, null, null, null));

        userRepository.registerUserAndGetEntity(
                cleanName,
                cleanDpi,
                cleanPassword,
                cleanPhones,
                cleanAddress,
                nip,
                role,
                serviceType,
                stationNodeId,
                stationLat,
                stationLng,
                new UserRepository.RepositoryCallback<>() {
                    @Override
                    public void onSuccess(User createdUser) {
                        String detail = role == UserRole.AGENTE
                                ? String.format(Locale.US, " (%s - %s)", serviceType.getDisplayName(), cleanAddress)
                                : " (Rol: ADMINISTRADOR)";
                        setOrPost(uiState, new RegistrationUiState(
                                false,
                                createdUser.getId(),
                                ApprovalStatus.APPROVED,
                                "Usuario ID #" + createdUser.getId() + " (DPI: " + createdUser.getDpi()
                                        + ") creado, auto-aprobado y sincronizado" + detail + ".",
                                null
                        ));
                        setOrPost(authError, null);
                        setOrPost(authenticatedUser, createdUser);
                    }

                    @Override
                    public void onError(Throwable error) {
                        postError(error.getMessage());
                    }
                }
        );
    }

    public void updateUserStationLocation(
            long userId,
            long stationNodeId,
            double stationLat,
            double stationLng,
            @NonNull String stationName
    ) {
        setOrPost(uiState, new RegistrationUiState(true, userId, null, null, null));
        userRepository.updateUserStation(
                userId,
                stationNodeId,
                stationLat,
                stationLng,
                stationName,
                new UserRepository.RepositoryCallback<>() {
                    @Override
                    public void onSuccess(User updatedUser) {
                        setOrPost(uiState, new RegistrationUiState(
                                false,
                                userId,
                                updatedUser != null ? updatedUser.getApprovalStatus() : null,
                                String.format(
                                        Locale.US,
                                        "Estacion actualizada para %s: %s (%.5f, %.5f)",
                                        updatedUser != null ? updatedUser.getFullName() : ("ID " + userId),
                                        stationName,
                                        stationLat,
                                        stationLng
                                ),
                                null
                        ));
                    }

                    @Override
                    public void onError(Throwable error) {
                        postError(error.getMessage());
                    }
                }
        );
    }

    public void deleteUser(long userId, @NonNull EmergencyOrderDao orderDao) {
        setOrPost(uiState, new RegistrationUiState(true, userId, null, null, null));
        userRepository.deleteUserById(userId, orderDao, new UserRepository.RepositoryCallback<>() {
            @Override
            public void onSuccess(Boolean deleted) {
                setOrPost(uiState, new RegistrationUiState(
                        false,
                        userId,
                        null,
                        "Usuario ID #" + userId + " eliminado correctamente.",
                        null
                ));
            }

            @Override
            public void onError(Throwable error) {
                postError(error.getMessage());
            }
        });
    }

    public void clearAndResetAllUsers(@NonNull GeoRutaDatabase database) {
        setOrPost(uiState, new RegistrationUiState(true, null, null, null, null));
        userRepository.clearAndResetUsersToSeed(database, new UserRepository.RepositoryCallback<>() {
            @Override
            public void onSuccess(Boolean ok) {
                setOrPost(uiState, new RegistrationUiState(
                        false,
                        null,
                        null,
                        "Todos los usuarios y ordenes han sido eliminados correctamente.",
                        null
                ));
            }

            @Override
            public void onError(Throwable error) {
                postError(error.getMessage());
            }
        });
    }

    private void postError(String message) {
        setOrPost(uiState, new RegistrationUiState(false, null, null, null, message));
    }
}
