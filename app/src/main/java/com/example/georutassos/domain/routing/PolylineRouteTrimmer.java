package com.example.georutassos.domain.routing;

import com.example.georutassos.domain.model.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utilidad matemática de Dominio para el manejo dinámico de Polylines de Google Maps:
 * <ul>
 *     <li>Verifica si el agente se desvió de la ruta más allá del umbral permitido.</li>
 *     <li>Recorta (trims) la Polyline descartando los segmentos ya recorridos y proyectando
 *     la posición actual del agente sobre el segmento activo para una visualización fluida.</li>
 * </ul>
 */
public final class PolylineRouteTrimmer {

    /** Umbral por defecto (en metros) para considerar que el agente se salió de la ruta. */
    public static final double DEFAULT_OFF_ROUTE_THRESHOLD_METERS = 35.0;

    private PolylineRouteTrimmer() {
    }

    /**
     * Resultado del análisis geométrico de la ubicación del agente respecto a la Polyline activa.
     *
     * @param isOffRoute           {@code true} si el agente excedió la distancia máxima hacia la ruta
     *                             y se debe disparar un recálculo con Dijkstra.
     * @param trimmedPolyline      Lista actualizada de coordenadas con los tramos superados ya recortados.
     * @param distanceToRouteMeters Distancia perpendicular mínima (en metros) desde el agente a la ruta.
     */
    public record PolylineUpdateResult(
            boolean isOffRoute,
            List<GeoPoint> trimmedPolyline,
            double distanceToRouteMeters
    ) {}

    /**
     * Evalúa la posición actual del agente contra la Polyline activa y devuelve la ruta recortada
     * o indica si requiere recálculo con Dijkstra.
     */
    public static PolylineUpdateResult evaluateAndTrim(
            GeoPoint currentLocation,
            List<GeoPoint> currentPolyline,
            double offRouteThresholdMeters
    ) {
        if (currentPolyline == null || currentPolyline.isEmpty()) {
            return new PolylineUpdateResult(true, Collections.emptyList(), Double.MAX_VALUE);
        }

        if (currentPolyline.size() == 1) {
            double dist = currentLocation.distanceToMeters(currentPolyline.get(0));
            boolean offRoute = dist > offRouteThresholdMeters;
            return new PolylineUpdateResult(offRoute, List.copyOf(currentPolyline), dist);
        }

        int closestSegmentIndex = 0;
        double minDistanceMeters = Double.MAX_VALUE;
        GeoPoint bestProjectedPoint = currentPolyline.get(0);

        for (int i = 0; i < currentPolyline.size() - 1; i++) {
            GeoPoint segmentStart = currentPolyline.get(i);
            GeoPoint segmentEnd = currentPolyline.get(i + 1);

            GeoPoint projected = projectPointOnSegment(currentLocation, segmentStart, segmentEnd);
            double distance = currentLocation.distanceToMeters(projected);

            if (distance < minDistanceMeters) {
                minDistanceMeters = distance;
                closestSegmentIndex = i;
                bestProjectedPoint = projected;
            }
        }

        if (minDistanceMeters > offRouteThresholdMeters) {
            // El agente se desvió de la ruta trazada
            return new PolylineUpdateResult(true, currentPolyline, minDistanceMeters);
        }

        // Recortar todos los vértices anteriores al segmento actual y colocar como primer punto
        // la proyección exacta del agente sobre el segmento activo.
        List<GeoPoint> trimmed = new ArrayList<>(currentPolyline.size() - closestSegmentIndex);
        trimmed.add(bestProjectedPoint);
        for (int i = closestSegmentIndex + 1; i < currentPolyline.size(); i++) {
            trimmed.add(currentPolyline.get(i));
        }

        return new PolylineUpdateResult(false, trimmed, minDistanceMeters);
    }

    /**
     * Proyecta ortogonalmente el punto P sobre el segmento definido por A y B.
     */
    private static GeoPoint projectPointOnSegment(GeoPoint p, GeoPoint a, GeoPoint b) {
        double ax = a.longitude();
        double ay = a.latitude();
        double bx = b.longitude();
        double by = b.latitude();
        double px = p.longitude();
        double py = p.latitude();

        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;

        if (lengthSquared == 0.0) {
            return a;
        }

        double t = ((px - ax) * dx + (py - ay) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));

        double projLat = ay + t * dy;
        double projLng = ax + t * dx;
        return new GeoPoint(projLat, projLng);
    }
}
