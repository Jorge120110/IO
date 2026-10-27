package com.example.georutassos.domain.model;

/**
 * Estado del ciclo de vida de una Orden de Emergencia.
 */
public enum OrderStatus {
    /** Orden asignada a un agente pendiente de iniciar o en despacho. */
    ASSIGNED,
    /** El agente está navegando hacia el destino en tiempo real. */
    IN_PROGRESS,
    /** El agente llegó al destino y completó la emergencia. */
    COMPLETED,
    /** Orden cancelada por el despachador o sistema. */
    CANCELLED
}
