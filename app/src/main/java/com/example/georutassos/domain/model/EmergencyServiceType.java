package com.example.georutassos.domain.model;

/**
 * Tipos de servicio de emergencia para los agentes operativos de GeoRuta SOS.
 */
public enum EmergencyServiceType {
    POLICIA("Policia Nacional Civil"),
    HOSPITAL("Hospital y Paramedicos"),
    BOMBEROS("Cuerpo de Bomberos"),
    ADMINISTRACION("Administracion y Despacho");

    private final String displayName;

    EmergencyServiceType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
