package com.example.georutassos.domain.model;

/**
 * Coordenada geográfica inmutable (Java 17 record) utilizada en el dominio,
 * motor de Dijkstra y trazado de Polylines (convertible directamente a LatLng de Google Maps).
 */
public record GeoPoint(double latitude, double longitude) {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /**
     * Calcula la distancia en metros hacia otro punto usando la fórmula de Haversine.
     */
    public double distanceToMeters(GeoPoint other) {
        double dLat = Math.toRadians(other.latitude - this.latitude);
        double dLng = Math.toRadians(other.longitude - this.longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(this.latitude)) * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }
}
