package com.example.georutassos.domain.model;

/**
 * Roles del sistema GeoRuta SOS.
 */
public enum UserRole {
    /** Administrador global encargado de aprobar/rechazar registros de usuarios. */
    SUPER_USUARIO,
    /** Despachadores que crean órdenes de emergencia y monitorean el historial y agentes. */
    ADMINISTRADOR,
    /** Policías, bomberos o paramédicos que reciben y ejecutan la ruta de emergencia. */
    AGENTE
}
