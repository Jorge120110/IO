package com.example.georutassos.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.example.georutassos.domain.model.OrderStatus;

/**
 * Entidad Room que representa una Orden de Emergencia asignada por un ADMINISTRADOR a un AGENTE.
 * Almacena tanto los IDs de nodos del grafo para Dijkstra como las coordenadas de origen/destino
 * y metricas de auditoria (tiempos y conteo de recalculos por desvio).
 */
@Entity(
        tableName = "emergency_orders",
        indices = {
                @Index(value = {"assigned_agent_id"}),
                @Index(value = {"dispatcher_admin_id"}),
                @Index(value = {"status"})
        }
)
public class EmergencyOrder {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    private long id;

    @ColumnInfo(name = "assigned_agent_id")
    private long assignedAgentId;

    @ColumnInfo(name = "dispatcher_admin_id")
    private long dispatcherAdminId;

    @ColumnInfo(name = "origin_node_id")
    private long originNodeId;

    @ColumnInfo(name = "origin_lat")
    private double originLat;

    @ColumnInfo(name = "origin_lng")
    private double originLng;

    @ColumnInfo(name = "destination_node_id")
    private long destinationNodeId;

    @ColumnInfo(name = "destination_lat")
    private double destinationLat;

    @ColumnInfo(name = "destination_lng")
    private double destinationLng;

    @Nullable
    @ColumnInfo(name = "description")
    private String description;

    @NonNull
    @ColumnInfo(name = "status")
    private OrderStatus status;

    @ColumnInfo(name = "assigned_at")
    private long assignedAt;

    @Nullable
    @ColumnInfo(name = "completed_at")
    private Long completedAt;

    /** Cantidad de recálculos de ruta por desvíos (útil para auditoría de comportamiento). */
    @ColumnInfo(name = "recalculation_count")
    private int recalculationCount;

    public EmergencyOrder(
            long assignedAgentId,
            long dispatcherAdminId,
            long originNodeId,
            double originLat,
            double originLng,
            long destinationNodeId,
            double destinationLat,
            double destinationLng,
            @Nullable String description,
            @NonNull OrderStatus status,
            long assignedAt,
            @Nullable Long completedAt,
            int recalculationCount
    ) {
        this.assignedAgentId = assignedAgentId;
        this.dispatcherAdminId = dispatcherAdminId;
        this.originNodeId = originNodeId;
        this.originLat = originLat;
        this.originLng = originLng;
        this.destinationNodeId = destinationNodeId;
        this.destinationLat = destinationLat;
        this.destinationLng = destinationLng;
        this.description = description;
        this.status = status;
        this.assignedAt = assignedAt;
        this.completedAt = completedAt;
        this.recalculationCount = recalculationCount;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getAssignedAgentId() {
        return assignedAgentId;
    }

    public void setAssignedAgentId(long assignedAgentId) {
        this.assignedAgentId = assignedAgentId;
    }

    public long getDispatcherAdminId() {
        return dispatcherAdminId;
    }

    public void setDispatcherAdminId(long dispatcherAdminId) {
        this.dispatcherAdminId = dispatcherAdminId;
    }

    public long getOriginNodeId() {
        return originNodeId;
    }

    public void setOriginNodeId(long originNodeId) {
        this.originNodeId = originNodeId;
    }

    public double getOriginLat() {
        return originLat;
    }

    public void setOriginLat(double originLat) {
        this.originLat = originLat;
    }

    public double getOriginLng() {
        return originLng;
    }

    public void setOriginLng(double originLng) {
        this.originLng = originLng;
    }

    public long getDestinationNodeId() {
        return destinationNodeId;
    }

    public void setDestinationNodeId(long destinationNodeId) {
        this.destinationNodeId = destinationNodeId;
    }

    public double getDestinationLat() {
        return destinationLat;
    }

    public void setDestinationLat(double destinationLat) {
        this.destinationLat = destinationLat;
    }

    public double getDestinationLng() {
        return destinationLng;
    }

    public void setDestinationLng(double destinationLng) {
        this.destinationLng = destinationLng;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    @NonNull
    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(@NonNull OrderStatus status) {
        this.status = status;
    }

    public long getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(long assignedAt) {
        this.assignedAt = assignedAt;
    }

    @Nullable
    public Long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(@Nullable Long completedAt) {
        this.completedAt = completedAt;
    }

    public int getRecalculationCount() {
        return recalculationCount;
    }

    public void setRecalculationCount(int recalculationCount) {
        this.recalculationCount = recalculationCount;
    }
}
