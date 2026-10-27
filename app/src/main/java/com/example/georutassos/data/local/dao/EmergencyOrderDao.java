package com.example.georutassos.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.georutassos.data.local.entity.EmergencyOrder;
import com.example.georutassos.domain.model.OrderStatus;

import java.util.List;

/**
 * DAO para inserción de órdenes de emergencia, seguimiento activo y consultas de historial
 * para auditoría de comportamiento de agentes.
 */
@Dao
public interface EmergencyOrderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertOrder(EmergencyOrder order);

    @Query("SELECT * FROM emergency_orders WHERE id = :orderId LIMIT 1")
    EmergencyOrder getOrderByIdSync(long orderId);

    /**
     * Observa la orden activa (ASSIGNED o IN_PROGRESS) más reciente para un agente específico.
     */
    @Query("SELECT * FROM emergency_orders " +
            "WHERE assigned_agent_id = :agentId " +
            "AND status IN ('ASSIGNED', 'IN_PROGRESS') " +
            "ORDER BY assigned_at DESC LIMIT 1")
    LiveData<EmergencyOrder> observeActiveOrderForAgent(long agentId);

    @Query("SELECT * FROM emergency_orders " +
            "WHERE assigned_agent_id = :agentId " +
            "AND status IN ('ASSIGNED', 'IN_PROGRESS') " +
            "ORDER BY assigned_at DESC LIMIT 1")
    EmergencyOrder getActiveOrderForAgentSync(long agentId);

    /**
     * Historial de órdenes asignadas a un usuario/agente específico para auditoría de comportamiento.
     */
    @Query("SELECT * FROM emergency_orders " +
            "WHERE assigned_agent_id = :agentId " +
            "ORDER BY assigned_at DESC")
    LiveData<List<EmergencyOrder>> observeOrderHistoryForAgent(long agentId);

    /**
     * Consulta síncrona de historial de órdenes por agente (útil para reportes de auditoría).
     */
    @Query("SELECT * FROM emergency_orders " +
            "WHERE assigned_agent_id = :agentId " +
            "ORDER BY assigned_at DESC")
    List<EmergencyOrder> getOrderHistoryForAgentSync(long agentId);

    /**
     * Historial global de todas las órdenes despachadas en el sistema.
     */
    @Query("SELECT * FROM emergency_orders ORDER BY assigned_at DESC")
    LiveData<List<EmergencyOrder>> observeAllOrdersHistory();

    @Query("SELECT * FROM emergency_orders ORDER BY assigned_at DESC")
    List<EmergencyOrder> getAllOrdersSync();

    @Query("SELECT * FROM emergency_orders WHERE assigned_at = :assignedAt LIMIT 1")
    EmergencyOrder getOrderByAssignedAtSync(long assignedAt);

    @Query("UPDATE emergency_orders SET status = :status, completed_at = :completedAt, recalculation_count = :recalculationCount WHERE id = :orderId")
    int updateOrderSyncState(long orderId, OrderStatus status, Long completedAt, int recalculationCount);

    @Query("UPDATE emergency_orders SET status = :status, completed_at = :completedAt WHERE id = :orderId")
    int updateOrderStatus(long orderId, OrderStatus status, Long completedAt);

    @Query("UPDATE emergency_orders SET status = :status, completed_at = :completedAt WHERE assigned_agent_id = :agentId AND status IN ('ASSIGNED', 'IN_PROGRESS')")
    int completeActiveOrdersForAgent(long agentId, OrderStatus status, Long completedAt);

    @Query("UPDATE emergency_orders SET recalculation_count = recalculation_count + 1 WHERE id = :orderId")
    int incrementRecalculationCount(long orderId);

    @Query("DELETE FROM emergency_orders WHERE assigned_agent_id = :userId OR dispatcher_admin_id = :userId")
    int deleteOrdersForUser(long userId);

    @Query("DELETE FROM emergency_orders")
    int deleteAllOrders();
}
