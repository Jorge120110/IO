package com.example.georutassos.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.georutassos.data.local.entity.User;
import com.example.georutassos.domain.model.ApprovalStatus;
import com.example.georutassos.domain.model.EmergencyServiceType;
import com.example.georutassos.domain.model.UserRole;

import java.util.List;

/**
 * DAO para inserción, validación de registro, búsqueda por DPI y gestión de roles/aprobaciones de usuarios.
 */
@Dao
public interface UserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertUser(User user);

    @Query("UPDATE users SET full_name = :fullName, password = :password, phones = :phones, " +
            "address = :address, nip = :nip, role = :role, service_type = :serviceType, " +
            "approval_status = :approvalStatus, station_node_id = :stationNodeId, " +
            "station_lat = :stationLat, station_lng = :stationLng WHERE dpi = :dpi")
    int updateUserByDpi(
            String dpi,
            String fullName,
            String password,
            String phones,
            String address,
            String nip,
            UserRole role,
            EmergencyServiceType serviceType,
            ApprovalStatus approvalStatus,
            long stationNodeId,
            double stationLat,
            double stationLng
    );

    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    User getUserByIdSync(long userId);

    @Query("SELECT * FROM users WHERE TRIM(dpi) = TRIM(:dpi) OR REPLACE(dpi, ' ', '') = REPLACE(:dpi, ' ', '') LIMIT 1")
    User getUserByDpiSync(String dpi);

    @Query("SELECT * FROM users WHERE dpi = :dpi AND password = :password LIMIT 1")
    User authenticateByDpiAndPasswordSync(String dpi, String password);

    @Query("SELECT * FROM users WHERE role IN ('ADMINISTRADOR', 'SUPER_USUARIO') ORDER BY id ASC LIMIT 1")
    User getFirstAdminUserSync();

    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE dpi = :dpi)")
    boolean existsByDpi(String dpi);

    @Query("SELECT * FROM users ORDER BY id ASC")
    LiveData<List<User>> observeAllUsers();

    @Query("SELECT * FROM users ORDER BY id ASC")
    List<User> getAllUsersSync();

    @Query("SELECT * FROM users WHERE approval_status = :status ORDER BY created_at ASC")
    LiveData<List<User>> observeUsersByApprovalStatus(ApprovalStatus status);

    @Query("SELECT * FROM users WHERE approval_status = :status ORDER BY created_at ASC")
    List<User> getUsersByApprovalStatusSync(ApprovalStatus status);

    @Query("SELECT * FROM users WHERE role = :role AND approval_status = :status ORDER BY id ASC")
    LiveData<List<User>> observeUsersByRoleAndStatus(UserRole role, ApprovalStatus status);

    @Query("SELECT * FROM users WHERE role = :role AND approval_status = :status ORDER BY id ASC")
    List<User> getUsersByRoleAndStatusSync(UserRole role, ApprovalStatus status);

    @Query("SELECT * FROM users WHERE role = 'AGENTE' AND service_type = :serviceType AND approval_status = 'APPROVED' ORDER BY id ASC")
    List<User> getApprovedAgentsByServiceSync(EmergencyServiceType serviceType);

    @Query("UPDATE users SET approval_status = :newStatus WHERE id = :userId")
    int updateApprovalStatus(long userId, ApprovalStatus newStatus);

    @Query("UPDATE users SET station_node_id = :stationNodeId, station_lat = :stationLat, station_lng = :stationLng, address = :stationName WHERE id = :userId")
    int updateUserStation(long userId, long stationNodeId, double stationLat, double stationLng, String stationName);

    @Query("DELETE FROM users WHERE id = :userId")
    int deleteUserById(long userId);

    @Query("DELETE FROM users")
    int deleteAllUsers();
}
