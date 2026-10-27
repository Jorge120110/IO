package com.example.georutassos.domain.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.georutassos.data.local.dao.GraphDao;
import com.example.georutassos.data.local.entity.GraphNodeEntity;
import com.example.georutassos.data.local.entity.User;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Generador dinamico de estaciones cercanas (Policia, Hospitales y Estaciones de Bomberos)
 * calculadas directamente en funcion de la ubicacion GPS exacta del usuario solicitante
 * y vinculadas a los nodos viales mas cercanos de {@code corredor_suroriente.db}.
 */
public final class PresetStationCatalog {

    public record StationOption(
            @NonNull EmergencyServiceType serviceType,
            @NonNull String name,
            long nodeId,
            double latitude,
            double longitude,
            double distanceMetersFromApplicant
    ) {
        public StationOption(
                @NonNull EmergencyServiceType serviceType,
                @NonNull String name,
                long nodeId,
                double latitude,
                double longitude
        ) {
            this(serviceType, name, nodeId, latitude, longitude, 0.0);
        }

        @NonNull
        public StationOption withDistanceFrom(@Nullable GeoPoint applicantPoint) {
            if (applicantPoint == null) {
                return this;
            }
            double meters = applicantPoint.distanceToMeters(new GeoPoint(latitude, longitude));
            return new StationOption(serviceType, name, nodeId, latitude, longitude, meters);
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(
                    Locale.US,
                    "%s [%.5f, %.5f] (a %.2f km)",
                    name,
                    latitude,
                    longitude,
                    Math.max(0.0, distanceMetersFromApplicant) / 1000.0
            );
        }
    }

    private record RelativeTemplate(
            @NonNull String baseTitle,
            double deltaLat,
            double deltaLng,
            boolean keepExactUserCoord
    ) {}

    private static final List<RelativeTemplate> POLICIA_TEMPLATES = List.of(
            new RelativeTemplate("Subestacion PNC Sector Norte", 0.0031, 0.0007, false),
            new RelativeTemplate("Comisaria PNC Sector Oriente", -0.0022, 0.0051, false),
            new RelativeTemplate("Delegacion PNC Sector Occidente", 0.0019, -0.0076, false),
            new RelativeTemplate("Destacamento PNC Sector Sur", -0.0104, -0.0021, false)
    );

    private static final List<RelativeTemplate> HOSPITAL_TEMPLATES = List.of(
            new RelativeTemplate("Centro de Emergencia Medica Sector Norte", 0.0028, -0.0011, false),
            new RelativeTemplate("Hospital / Urgencias Sector Oriente", 0.0014, 0.0054, false),
            new RelativeTemplate("Centro Hospitalario Sector Occidente", -0.0029, -0.0073, false),
            new RelativeTemplate("Hospital Regional Sector Sur", -0.0108, 0.0025, false)
    );

    private static final List<RelativeTemplate> BOMBEROS_TEMPLATES = List.of(
            new RelativeTemplate("Compania de Bomberos Sector Norte", 0.0034, 0.0014, false),
            new RelativeTemplate("Estacion Bomberos Voluntarios Sector Oriente", -0.0035, 0.0046, false),
            new RelativeTemplate("Estacion Bomberos Municipales Sector Occidente", 0.0038, -0.0069, false),
            new RelativeTemplate("Estacion Central de Bomberos Sector Sur", -0.0112, -0.0010, false)
    );

    private PresetStationCatalog() {
    }

    /**
     * Genera dinamicamente las estaciones cercanas para el servicio elegido alrededor de la
     * ubicacion exacta del solicitante, ajustandolas a nodos viales reales si {@code graphDao}
     * esta disponible y ordenandolas por distancia real en metros.
     */
    @NonNull
    public static List<StationOption> getDynamicStationsNearApplicant(
            @NonNull EmergencyServiceType serviceType,
            @NonNull GeoPoint applicantPosition,
            @Nullable String areaName,
            @Nullable GraphDao graphDao
    ) {
        List<RelativeTemplate> templates = switch (serviceType) {
            case POLICIA, ADMINISTRACION -> POLICIA_TEMPLATES;
            case HOSPITAL -> HOSPITAL_TEMPLATES;
            case BOMBEROS -> BOMBEROS_TEMPLATES;
        };

        String cleanArea = (areaName != null && !areaName.isBlank())
                ? (" - " + areaName.trim())
                : "";

        List<StationOption> dynamicOptions = new ArrayList<>(templates.size());
        long fallbackNodeId = User.DEFAULT_STATION_NODE_ID;

        if (graphDao != null) {
            try {
                GraphNodeEntity userNearest = graphDao.findNearestNodeInWindowSync(
                        applicantPosition.latitude(),
                        applicantPosition.longitude(),
                        0.05
                );
                if (userNearest == null) {
                    userNearest = graphDao.findNearestNodeSync(
                            applicantPosition.latitude(),
                            applicantPosition.longitude()
                    );
                }
                if (userNearest != null) {
                    fallbackNodeId = userNearest.getNodeId();
                }
            } catch (Throwable ignored) {
            }
        }

        for (RelativeTemplate tpl : templates) {
            double targetLat = applicantPosition.latitude() + tpl.deltaLat();
            double targetLng = applicantPosition.longitude() + tpl.deltaLng();
            long resolvedNodeId = fallbackNodeId;
            double finalLat = targetLat;
            double finalLng = targetLng;

            if (graphDao != null) {
                try {
                    GraphNodeEntity snapNode = graphDao.findNearestNodeInWindowSync(
                            targetLat,
                            targetLng,
                            0.005
                    );
                    if (snapNode != null) {
                        resolvedNodeId = snapNode.getNodeId();
                        if (!tpl.keepExactUserCoord()) {
                            finalLat = snapNode.getLatitude();
                            finalLng = snapNode.getLongitude();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            double distanceMeters = tpl.keepExactUserCoord()
                    ? 0.0
                    : applicantPosition.distanceToMeters(new GeoPoint(finalLat, finalLng));

            String stationTitle = tpl.baseTitle() + cleanArea;
            dynamicOptions.add(new StationOption(
                    serviceType,
                    stationTitle,
                    resolvedNodeId,
                    finalLat,
                    finalLng,
                    distanceMeters
            ));
        }

        dynamicOptions.sort(Comparator.comparingDouble(StationOption::distanceMetersFromApplicant));
        return dynamicOptions;
    }

    @NonNull
    public static List<StationOption> getStationsSortedByDistance(
            @NonNull EmergencyServiceType serviceType,
            @Nullable GeoPoint applicantPosition
    ) {
        GeoPoint ref = applicantPosition != null
                ? applicantPosition
                : new GeoPoint(User.DEFAULT_STATION_LAT, User.DEFAULT_STATION_LNG);
        return getDynamicStationsNearApplicant(serviceType, ref, null, null);
    }
}
