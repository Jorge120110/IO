package com.example.georutassos.domain.routing;

import androidx.annotation.NonNull;

import com.example.georutassos.data.local.dao.GraphDao;
import com.example.georutassos.data.local.entity.GraphEdgeEntity;
import com.example.georutassos.data.local.entity.GraphNodeEntity;
import com.example.georutassos.domain.model.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Envoltorio (Wrapper) que conecta la base de datos local Room ({@link GraphDao}) con tu
 * algoritmo de Dijkstra existente.
 * <p>
 * Mantiene una caché en memoria de la lista de adyacencia para garantizar tiempos de cálculo
 * mínimos durante emergencias y recálculos en tiempo real.
 */
public class RoomDijkstraEngineWrapper implements DijkstraRouteEngine {

    /**
     * Interfaz funcional para que puedas inyectar directamente tu clase/algoritmo de Dijkstra
     * personalizado si prefieres delegar la resolución matemática.
     */
    @FunctionalInterface
    public interface CustomDijkstraSolver {
        List<Long> solveShortestNodeIds(
                long startNodeId,
                long endNodeId,
                Map<Long, List<AdjacencyEdge>> adjacencyList
        );
    }

    /** Representa una arista en la lista de adyacencia en memoria. */
    public record AdjacencyEdge(long targetNodeId, double weight) {}

    private final GraphDao graphDao;
    private final CustomDijkstraSolver customSolver;

    private final Map<Long, GeoPoint> nodeCoordinatesCache = new HashMap<>();
    private final Map<Long, List<AdjacencyEdge>> adjacencyListCache = new HashMap<>();
    private volatile boolean isGraphLoaded = false;

    /**
     * Constructor con el solver de Dijkstra con cola de prioridad integrado por defecto.
     */
    public RoomDijkstraEngineWrapper(@NonNull GraphDao graphDao) {
        this(graphDao, RoomDijkstraEngineWrapper::defaultPriorityQueueDijkstra);
    }

    /**
     * Constructor que permite inyectar tu propio algoritmo de Dijkstra existente.
     */
    public RoomDijkstraEngineWrapper(
            @NonNull GraphDao graphDao,
            @NonNull CustomDijkstraSolver customSolver
    ) {
        this.graphDao = graphDao;
        this.customSolver = customSolver;
    }

    /**
     * Carga o refresca un subgrafo espacial desde SQLite hacia estructuras en memoria para
     * evitar saturar el heap de Android con los 186,050 nodos y 461,958 aristas completos.
     */
    public synchronized void ensureGraphLoaded() {
        if (isGraphLoaded) {
            return;
        }
        loadSubgraphForBox(14.58, 14.68, -90.62, -90.48);
        isGraphLoaded = true;
    }

    private void loadSubgraphForBox(
            double minLat,
            double maxLat,
            double minLng,
            double maxLng
    ) {
        nodeCoordinatesCache.clear();
        adjacencyListCache.clear();

        List<GraphNodeEntity> nodes = graphDao.getNodesInBoundingBoxSync(minLat, maxLat, minLng, maxLng);
        for (GraphNodeEntity node : nodes) {
            nodeCoordinatesCache.put(
                    node.getNodeId(),
                    new GeoPoint(node.getLatitude(), node.getLongitude())
            );
            adjacencyListCache.put(node.getNodeId(), new ArrayList<>(4));
        }

        List<GraphEdgeEntity> edges = graphDao.getEdgesInBoundingBoxSync(minLat, maxLat, minLng, maxLng);
        for (GraphEdgeEntity edge : edges) {
            List<AdjacencyEdge> forwardList = adjacencyListCache.get(edge.getFromNodeId());
            if (forwardList != null) {
                forwardList.add(new AdjacencyEdge(edge.getToNodeId(), edge.getWeight()));
            }
            // Permite a unidades de emergencia salir/entrar de segmentos OSM unidireccionales con penalizacion
            List<AdjacencyEdge> reverseList = adjacencyListCache.get(edge.getToNodeId());
            if (reverseList != null) {
                reverseList.add(new AdjacencyEdge(edge.getFromNodeId(), edge.getWeight() * 1.25));
            }
        }
    }

    /** Fuerza la recarga del grafo desde Room SQLite si los pesos o aristas cambiaron. */
    public synchronized void invalidateCache() {
        isGraphLoaded = false;
        nodeCoordinatesCache.clear();
        adjacencyListCache.clear();
    }

    @Override
    public synchronized List<GeoPoint> calculateShortestPath(long startNodeId, long endNodeId) {
        GraphNodeEntity startNode = graphDao.getNodeByIdSync(startNodeId);
        GraphNodeEntity endNode = graphDao.getNodeByIdSync(endNodeId);
        if (startNode == null || endNode == null) {
            return Collections.emptyList();
        }

        double[] margins = {0.018, 0.045, 0.10};
        for (double margin : margins) {
            double minLat = Math.min(startNode.getLatitude(), endNode.getLatitude()) - margin;
            double maxLat = Math.max(startNode.getLatitude(), endNode.getLatitude()) + margin;
            double minLng = Math.min(startNode.getLongitude(), endNode.getLongitude()) - margin;
            double maxLng = Math.max(startNode.getLongitude(), endNode.getLongitude()) + margin;

            loadSubgraphForBox(minLat, maxLat, minLng, maxLng);

            List<Long> orderedNodeIds = customSolver.solveShortestNodeIds(
                    startNodeId,
                    endNodeId,
                    adjacencyListCache
            );

            if (!orderedNodeIds.isEmpty()) {
                List<GeoPoint> routePoints = new ArrayList<>(orderedNodeIds.size());
                for (Long nodeId : orderedNodeIds) {
                    GeoPoint point = nodeCoordinatesCache.get(nodeId);
                    if (point != null) {
                        routePoints.add(point);
                    }
                }
                return routePoints;
            }
        }

        // Respaldo defensivo si un nodo en OSM se encuentra en una calle cerrada/aislada:
        return List.of(
                new GeoPoint(startNode.getLatitude(), startNode.getLongitude()),
                new GeoPoint(endNode.getLatitude(), endNode.getLongitude())
        );
    }

    @Override
    public long findNearestNodeId(GeoPoint currentLocation) {
        GraphNodeEntity nearest = graphDao.findNearestNodeInWindowSync(
                currentLocation.latitude(),
                currentLocation.longitude(),
                0.015
        );
        if (nearest == null) {
            nearest = graphDao.findNearestNodeInWindowSync(
                    currentLocation.latitude(),
                    currentLocation.longitude(),
                    0.06
            );
        }
        if (nearest == null) {
            nearest = graphDao.findNearestNodeSync(
                    currentLocation.latitude(),
                    currentLocation.longitude()
            );
        }
        return nearest != null ? nearest.getNodeId() : -1L;
    }

    /**
     * Implementación base de Dijkstra O((V + E) log V) por si no se inyecta un solver externo.
     */
    private static List<Long> defaultPriorityQueueDijkstra(
            long startNodeId,
            long endNodeId,
            Map<Long, List<AdjacencyEdge>> adjacencyList
    ) {
        if (startNodeId == endNodeId) {
            return List.of(startNodeId);
        }

        record NodeDistance(long nodeId, double distance) {}

        Map<Long, Double> distances = new HashMap<>();
        Map<Long, Long> previous = new HashMap<>();
        PriorityQueue<NodeDistance> pq = new PriorityQueue<>(
                Comparator.comparingDouble(NodeDistance::distance)
        );

        distances.put(startNodeId, 0.0);
        pq.offer(new NodeDistance(startNodeId, 0.0));

        while (!pq.isEmpty()) {
            NodeDistance current = pq.poll();
            if (current.nodeId() == endNodeId) {
                break;
            }
            if (current.distance() > distances.getOrDefault(current.nodeId(), Double.MAX_VALUE)) {
                continue;
            }

            List<AdjacencyEdge> neighbors = adjacencyList.getOrDefault(
                    current.nodeId(),
                    Collections.emptyList()
            );
            for (AdjacencyEdge edge : neighbors) {
                double newDist = current.distance() + edge.weight();
                if (newDist < distances.getOrDefault(edge.targetNodeId(), Double.MAX_VALUE)) {
                    distances.put(edge.targetNodeId(), newDist);
                    previous.put(edge.targetNodeId(), current.nodeId());
                    pq.offer(new NodeDistance(edge.targetNodeId(), newDist));
                }
            }
        }

        if (!previous.containsKey(endNodeId)) {
            return Collections.emptyList();
        }

        List<Long> path = new ArrayList<>();
        Long step = endNodeId;
        while (step != null) {
            path.add(step);
            step = previous.get(step);
        }
        Collections.reverse(path);
        return path;
    }
}
