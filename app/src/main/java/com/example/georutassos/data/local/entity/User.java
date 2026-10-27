package com.example.georutassos.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.example.georutassos.domain.model.ApprovalStatus;
import com.example.georutassos.domain.model.EmergencyServiceType;
import com.example.georutassos.domain.model.UserRole;

/**
 * Entidad Room para Usuarios del sistema GeoRuta SOS.
 * Incluye indice unico en DPI, clave de acceso (password), el tipo de servicio de emergencia
 * ({@link EmergencyServiceType}) y la ubicacion exacta de su Estacion Base.
 */
@Entity(
        tableName = "users",
        indices = {@Index(value = {"dpi"}, unique = true)}
)
public class User {

    public static final long DEFAULT_STATION_NODE_ID = 240L;
    public static final double DEFAULT_STATION_LAT = 14.6277121;
    public static final double DEFAULT_STATION_LNG = -90.5602439;
    public static final String DEFAULT_PASSWORD = "1234";

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    private long id;

    @NonNull
    @ColumnInfo(name = "full_name")
    private String fullName;

    @NonNull
    @ColumnInfo(name = "dpi")
    private String dpi;

    @NonNull
    @ColumnInfo(name = "password")
    private String password;

    @NonNull
    @ColumnInfo(name = "phones")
    private String phones;

    @NonNull
    @ColumnInfo(name = "address")
    private String address;

    /** Numero de Identificacion Policial (opcional, aplica para policias). */
    @Nullable
    @ColumnInfo(name = "nip")
    private String nip;

    @NonNull
    @ColumnInfo(name = "role")
    private UserRole role;

    @NonNull
    @ColumnInfo(name = "service_type")
    private EmergencyServiceType serviceType;

    @NonNull
    @ColumnInfo(name = "approval_status")
    private ApprovalStatus approvalStatus;

    @ColumnInfo(name = "station_node_id")
    private long stationNodeId;

    @ColumnInfo(name = "station_lat")
    private double stationLat;

    @ColumnInfo(name = "station_lng")
    private double stationLng;

    @ColumnInfo(name = "created_at")
    private long createdAt;

    @Ignore
    public User(
            @NonNull String fullName,
            @NonNull String dpi,
            @NonNull String phones,
            @NonNull String address,
            @Nullable String nip,
            @NonNull UserRole role,
            @NonNull ApprovalStatus approvalStatus,
            long createdAt
    ) {
        this(
                fullName,
                dpi,
                DEFAULT_PASSWORD,
                phones,
                address,
                nip,
                role,
                role == UserRole.AGENTE ? EmergencyServiceType.POLICIA : EmergencyServiceType.ADMINISTRACION,
                approvalStatus,
                DEFAULT_STATION_NODE_ID,
                DEFAULT_STATION_LAT,
                DEFAULT_STATION_LNG,
                createdAt
        );
    }

    @Ignore
    public User(
            @NonNull String fullName,
            @NonNull String dpi,
            @NonNull String phones,
            @NonNull String address,
            @Nullable String nip,
            @NonNull UserRole role,
            @NonNull EmergencyServiceType serviceType,
            @NonNull ApprovalStatus approvalStatus,
            long stationNodeId,
            double stationLat,
            double stationLng,
            long createdAt
    ) {
        this(
                fullName,
                dpi,
                DEFAULT_PASSWORD,
                phones,
                address,
                nip,
                role,
                serviceType,
                approvalStatus,
                stationNodeId,
                stationLat,
                stationLng,
                createdAt
        );
    }

    public User(
            @NonNull String fullName,
            @NonNull String dpi,
            @NonNull String password,
            @NonNull String phones,
            @NonNull String address,
            @Nullable String nip,
            @NonNull UserRole role,
            @NonNull EmergencyServiceType serviceType,
            @NonNull ApprovalStatus approvalStatus,
            long stationNodeId,
            double stationLat,
            double stationLng,
            long createdAt
    ) {
        this.fullName = fullName;
        this.dpi = dpi;
        this.password = password;
        this.phones = phones;
        this.address = address;
        this.nip = nip;
        this.role = role;
        this.serviceType = serviceType;
        this.approvalStatus = approvalStatus;
        this.stationNodeId = stationNodeId;
        this.stationLat = stationLat;
        this.stationLng = stationLng;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @NonNull
    public String getFullName() {
        return fullName;
    }

    public void setFullName(@NonNull String fullName) {
        this.fullName = fullName;
    }

    @NonNull
    public String getDpi() {
        return dpi;
    }

    public void setDpi(@NonNull String dpi) {
        this.dpi = dpi;
    }

    @NonNull
    public String getPassword() {
        return password;
    }

    public void setPassword(@NonNull String password) {
        this.password = password;
    }

    @NonNull
    public String getPhones() {
        return phones;
    }

    public void setPhones(@NonNull String phones) {
        this.phones = phones;
    }

    @NonNull
    public String getAddress() {
        return address;
    }

    public void setAddress(@NonNull String address) {
        this.address = address;
    }

    @Nullable
    public String getNip() {
        return nip;
    }

    public void setNip(@Nullable String nip) {
        this.nip = nip;
    }

    @NonNull
    public UserRole getRole() {
        return role;
    }

    public void setRole(@NonNull UserRole role) {
        this.role = role;
    }

    @NonNull
    public EmergencyServiceType getServiceType() {
        return serviceType;
    }

    public void setServiceType(@NonNull EmergencyServiceType serviceType) {
        this.serviceType = serviceType;
    }

    @NonNull
    public ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    public void setApprovalStatus(@NonNull ApprovalStatus approvalStatus) {
        this.approvalStatus = approvalStatus;
    }

    public long getStationNodeId() {
        return stationNodeId;
    }

    public void setStationNodeId(long stationNodeId) {
        this.stationNodeId = stationNodeId;
    }

    public double getStationLat() {
        return stationLat;
    }

    public void setStationLat(double stationLat) {
        this.stationLat = stationLat;
    }

    public double getStationLng() {
        return stationLng;
    }

    public void setStationLng(double stationLng) {
        this.stationLng = stationLng;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
