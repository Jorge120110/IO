package com.example.georutassos.domain.routing;

import com.example.georutassos.domain.model.GeoPoint;

import java.util.List;

/**
 * Contrato de Dominio para el motor de ruteo basado en el algoritmo de Dijkstra.
 * Aquí puedes inyectar tu implementación existente de Dijkstra alimentada por Room SQLite.
 */
public interface DijkstraRouteEngine {

    /**
     * Calcula la ruta óptima (camino más corto) entre el nodo de origen y el nodo de destino.
     *
     * @param startNodeId ID del nodo de inicio en el grafo.
     * @param endNodeId   ID del nodo de destino en el grafo.
     * @return Lista ordenada de coordenadas {@link GeoPoint} que conforman la Polyline de la ruta.
     */
    List<GeoPoint> calculateShortestPath(long startNodeId, long endNodeId);

    /**
     * Encuentra el ID del nodo del grafo más cercano a una coordenada GPS en tiempo real.
     * Se utiliza cuando el agente se desvía del camino trazado y es necesario recalcular la ruta
     * desde su posición actual hacia el nodo de destino.
     *
     * @param currentLocation Posición GPS actual del agente.
     * @return ID del nodo más cercano en el grafo.
     */
    long findNearestNodeId(GeoPoint currentLocation);
}
