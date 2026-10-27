package com.example.georutassos.presentation.admin;

import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.example.georutassos.data.local.entity.EmergencyOrder;
import com.example.georutassos.data.local.entity.User;
import com.example.georutassos.data.repository.EmergencyRepository;
import com.example.georutassos.data.repository.UserRepository;
import com.example.georutassos.domain.model.EmergencyServiceType;
import com.example.georutassos.domain.model.GeoPoint;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * ViewModel para el Modulo 2 y Auditoria (Rol ADMINISTRADOR):
 * 1) Despacho automatico hacia la estacion mas cercana para 1, 2 o los 3 servicios
 *    (Policia, Hospital y Paramedicos, Bomberos).
 * 2) Busqueda exclusiva por DPI para visualizar la ficha del agente y el historial de sus ordenes.
 */
public class AdminMonitorViewModel extends ViewModel {

    public record DispatchUiState(
            boolean isDispatching,
            @Nullable EmergencyOrder lastDispatchedOrder,
            @NonNull List<EmergencyRepository.ServiceDispatchAssignment> lastAssignments,
            @Nullable String feedbackMessage,
            @Nullable String errorMessage
    ) {
        public static DispatchUiState idle() {
            return new DispatchUiState(false, null, Collections.emptyList(), null, null);
        }
    }

    private final EmergencyRepository emergencyRepository;
    private final UserRepository userRepository;

    private final MutableLiveData<DispatchUiState> dispatchUiState =
            new MutableLiveData<>(DispatchUiState.idle());

    private final MutableLiveData<Long> selectedAuditAgentId = new MutableLiveData<>();
    private final LiveData<List<EmergencyOrder>> selectedAgentAuditHistory;

    private final MutableLiveData<User> searchedAgentByDpi = new MutableLiveData<>(null);
    private final MutableLiveData<String> searchDpiFeedback = new MutableLiveData<>(null);

    public AdminMonitorViewModel(
            @NonNull EmergencyRepository emergencyRepository,
            @NonNull UserRepository userRepository
    ) {
        this.emergencyRepository = emergencyRepository;
        this.userRepository = userRepository;
        this.selectedAgentAuditHistory = Transformations.switchMap(
                selectedAuditAgentId,
                emergencyRepository::observeAgentAuditHistory
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

    public LiveData<DispatchUiState> getDispatchUiState() {
        return dispatchUiState;
    }

    public LiveData<List<User>> observeAvailableAgents() {
        return userRepository.observeApprovedAgents();
    }

    public LiveData<List<EmergencyOrder>> observeAllOrdersHistory() {
        return emergencyRepository.observeAllOrdersHistory();
    }

    public void selectAgentForBehaviorAudit(long agentId) {
        setOrPost(selectedAuditAgentId, agentId);
        emergencyRepository.refreshAgentOrdersAsync(agentId);
    }

    public LiveData<List<EmergencyOrder>> getSelectedAgentAuditHistory() {
        return selectedAgentAuditHistory;
    }

    public LiveData<User> getSearchedAgentByDpi() {
        return searchedAgentByDpi;
    }

    public LiveData<String> getSearchDpiFeedback() {
        return searchDpiFeedback;
    }

    /**
     * Busca un usuario o agente por su DPI y carga su informacion personal junto con sus ordenes.
     */
    public void searchAgentByDpi(@Nullable String dpi) {
        String cleanDpi = UserRepository.normalizeDpi(dpi);
        if (cleanDpi.isEmpty()) {
            setOrPost(searchedAgentByDpi, null);
            setOrPost(searchDpiFeedback, "Error: Ingrese un numero de DPI valido para buscar.");
            return;
        }
        userRepository.findUserByDpi(cleanDpi, new UserRepository.RepositoryCallback<>() {
            @Override
            public void onSuccess(User foundUser) {
                setOrPost(searchedAgentByDpi, foundUser);
                setOrPost(searchDpiFeedback, "Agente localizado por DPI: " + foundUser.getFullName());
                setOrPost(selectedAuditAgentId, foundUser.getId());
                emergencyRepository.refreshAgentOrdersAsync(foundUser.getId());
            }

            @Override
            public void onError(Throwable error) {
                setOrPost(searchedAgentByDpi, null);
                setOrPost(searchDpiFeedback, "Error: " + error.getMessage());
            }
        });
    }

    /**
     * Despacha automaticamente la emergencia a la estacion mas cercana de cada servicio seleccionado
     * (pudiendo elegir desde 1 hasta los 3 servicios: Policia, Hospital, Bomberos).
     */
    public void dispatchToNearestStationsForServices(
            long dispatcherAdminId,
            @NonNull List<EmergencyServiceType> selectedServices,
            @NonNull GeoPoint emergencyDestinationPoint,
            @Nullable String description
    ) {
        if (selectedServices.isEmpty()) {
            setOrPost(dispatchUiState, new DispatchUiState(
                    false,
                    null,
                    Collections.emptyList(),
                    null,
                    "Seleccione al menos un servicio para despachar (Policia, Hospital o Bomberos)."
            ));
            return;
        }

        setOrPost(dispatchUiState, new DispatchUiState(true, null, Collections.emptyList(), null, null));

        emergencyRepository.dispatchOrderToNearestStations(
                dispatcherAdminId,
                selectedServices,
                emergencyDestinationPoint,
                description,
                new EmergencyRepository.RepositoryCallback<>() {
                    @Override
                    public void onSuccess(List<EmergencyRepository.ServiceDispatchAssignment> assignments) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Ordenes despachadas automaticamente a la estacion mas cercana:\n");
                        for (EmergencyRepository.ServiceDispatchAssignment a : assignments) {
                            sb.append(String.format(
                                    Locale.US,
                                    "- %s -> %s (%s, a %.2f km) [Orden #%d]\n",
                                    a.serviceType().getDisplayName(),
                                    a.assignedAgent().getFullName(),
                                    a.assignedAgent().getAddress(),
                                    a.distanceToEmergencyMeters() / 1000.0,
                                    a.order().getId()
                            ));
                        }
                        EmergencyOrder lastOrder = assignments.get(assignments.size() - 1).order();
                        setOrPost(dispatchUiState, new DispatchUiState(
                                false,
                                lastOrder,
                                assignments,
                                sb.toString().trim(),
                                null
                        ));
                    }

                    @Override
                    public void onError(Throwable error) {
                        setOrPost(dispatchUiState, new DispatchUiState(
                                false,
                                null,
                                Collections.emptyList(),
                                null,
                                error.getMessage()
                        ));
                    }
                }
        );
    }
}
