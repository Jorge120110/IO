package com.example.georutassos.data.local.converter;

import androidx.annotation.Nullable;
import androidx.room.TypeConverter;

import com.example.georutassos.domain.model.ApprovalStatus;
import com.example.georutassos.domain.model.EmergencyServiceType;
import com.example.georutassos.domain.model.OrderStatus;
import com.example.georutassos.domain.model.UserRole;

/**
 * TypeConverters de Room para persistir enumeraciones de dominio en SQLite
 * con manejo defensivo ante valores nulos o desconocidos.
 */
public final class RoomTypeConverters {

    private RoomTypeConverters() {
    }

    @TypeConverter
    @Nullable
    public static String fromUserRole(@Nullable UserRole role) {
        return role == null ? null : role.name();
    }

    @TypeConverter
    @Nullable
    public static UserRole toUserRole(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return UserRole.AGENTE;
        }
        try {
            return UserRole.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return UserRole.AGENTE;
        }
    }

    @TypeConverter
    @Nullable
    public static String fromApprovalStatus(@Nullable ApprovalStatus status) {
        return status == null ? null : status.name();
    }

    @TypeConverter
    @Nullable
    public static ApprovalStatus toApprovalStatus(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return ApprovalStatus.APPROVED;
        }
        try {
            return ApprovalStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return ApprovalStatus.APPROVED;
        }
    }

    @TypeConverter
    @Nullable
    public static String fromOrderStatus(@Nullable OrderStatus status) {
        return status == null ? null : status.name();
    }

    @TypeConverter
    @Nullable
    public static OrderStatus toOrderStatus(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return OrderStatus.ASSIGNED;
        }
        try {
            return OrderStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return OrderStatus.ASSIGNED;
        }
    }

    @TypeConverter
    @Nullable
    public static String fromServiceType(@Nullable EmergencyServiceType serviceType) {
        return serviceType == null ? null : serviceType.name();
    }

    @TypeConverter
    @Nullable
    public static EmergencyServiceType toServiceType(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return EmergencyServiceType.POLICIA;
        }
        try {
            return EmergencyServiceType.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return EmergencyServiceType.POLICIA;
        }
    }
}
