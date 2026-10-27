package com.example.georutassos.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.georutassos.data.repository.EmergencyRepository;
import com.example.georutassos.data.repository.UserRepository;
import com.example.georutassos.presentation.admin.AdminMonitorViewModel;
import com.example.georutassos.presentation.agent.AgentNavigationViewModel;
import com.example.georutassos.presentation.registration.RegistrationViewModel;

/**
 * Fábrica centralizada para inyectar los repositorios en los ViewModels de GeoRuta SOS
 * respetando la inversión de dependencias de Clean Architecture.
 */
public class GeoRutaViewModelFactory implements ViewModelProvider.Factory {

    private final UserRepository userRepository;
    private final EmergencyRepository emergencyRepository;

    public GeoRutaViewModelFactory(
            @NonNull UserRepository userRepository,
            @NonNull EmergencyRepository emergencyRepository
    ) {
        this.userRepository = userRepository;
        this.emergencyRepository = emergencyRepository;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(RegistrationViewModel.class)) {
            return (T) new RegistrationViewModel(userRepository);
        }
        if (modelClass.isAssignableFrom(AdminMonitorViewModel.class)) {
            return (T) new AdminMonitorViewModel(emergencyRepository, userRepository);
        }
        if (modelClass.isAssignableFrom(AgentNavigationViewModel.class)) {
            return (T) new AgentNavigationViewModel(emergencyRepository);
        }
        throw new IllegalArgumentException("ViewModel desconocido: " + modelClass.getName());
    }
}
