package com.example.georutassos.data.local.database;

import com.example.georutassos.data.local.dao.UserDao;
import com.example.georutassos.data.local.entity.GraphEdgeEntity;
import com.example.georutassos.data.local.entity.GraphNodeEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Inicializador de base de datos para GeoRuta SOS.
 * Sin perfiles de prueba pre-cargados: todos los usuarios (Administradores y Agentes)
 * se crean directamente desde la aplicacion y se sincronizan en tiempo real.
 */
public final class DemoSeedDataInitializer {

    private DemoSeedDataInitializer() {
    }

    public static void seedIfEmpty(GeoRutaDatabase database) {
        GeoRutaDatabase.databaseWriteExecutor.execute(() -> {
            if (database.graphDao().countNodesSync() == 0) {
                seedGraph(database);
            }
        });
    }

    private static void seedGraph(GeoRutaDatabase database) {
        List<GraphNodeEntity> nodes = List.of(
                new GraphNodeEntity(1L, 14.6400, -90.5200),
                new GraphNodeEntity(2L, 14.6400, -90.5150),
                new GraphNodeEntity(3L, 14.6400, -90.5100),
                new GraphNodeEntity(4L, 14.6350, -90.5200),
                new GraphNodeEntity(5L, 14.6350, -90.5150),
                new GraphNodeEntity(6L, 14.6350, -90.5100),
                new GraphNodeEntity(7L, 14.6300, -90.5200),
                new GraphNodeEntity(8L, 14.6300, -90.5150),
                new GraphNodeEntity(9L, 14.6300, -90.5100)
        );
        database.graphDao().insertNodes(nodes);

        List<GraphEdgeEntity> edges = new ArrayList<>();
        addBidirectionalEdge(edges, 1L, 2L, 100.0);
        addBidirectionalEdge(edges, 2L, 5L, 100.0);
        addBidirectionalEdge(edges, 5L, 6L, 100.0);
        addBidirectionalEdge(edges, 6L, 9L, 100.0);
        addBidirectionalEdge(edges, 2L, 3L, 220.0);
        addBidirectionalEdge(edges, 3L, 6L, 220.0);
        addBidirectionalEdge(edges, 1L, 4L, 150.0);
        addBidirectionalEdge(edges, 4L, 5L, 180.0);
        addBidirectionalEdge(edges, 4L, 7L, 140.0);
        addBidirectionalEdge(edges, 7L, 8L, 130.0);
        addBidirectionalEdge(edges, 5L, 8L, 160.0);
        addBidirectionalEdge(edges, 8L, 9L, 120.0);

        database.graphDao().insertEdges(edges);
    }

    private static void addBidirectionalEdge(
            List<GraphEdgeEntity> edges,
            long nodeA,
            long nodeB,
            double weight
    ) {
        edges.add(new GraphEdgeEntity(nodeA, nodeB, weight));
        edges.add(new GraphEdgeEntity(nodeB, nodeA, weight));
    }

    public static void seedUsers(GeoRutaDatabase database) {
        // Sin usuarios de prueba predefinidos.
    }

    public static void ensureBaseUsersExistSync(UserDao userDao) {
        // Sin usuarios de prueba predefinidos.
    }
}
