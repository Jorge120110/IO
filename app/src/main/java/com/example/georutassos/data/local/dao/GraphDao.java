package com.example.georutassos.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.example.georutassos.data.local.entity.GraphEdgeEntity;
import com.example.georutassos.data.local.entity.GraphNodeEntity;

import java.util.List;

/**
 * DAO para consultar de forma ultra-rápida los nodos y aristas del grafo local en SQLite
 * y alimentar el algoritmo de Dijkstra en memoria.
 */
@Dao
public interface GraphDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertNodes(List<GraphNodeEntity> nodes);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertEdges(List<GraphEdgeEntity> edges);

    @Query("SELECT * FROM graph_nodes")
    List<GraphNodeEntity> getAllNodesSync();

    @Query("SELECT * FROM graph_edges")
    List<GraphEdgeEntity> getAllEdgesSync();

    @Query("SELECT COUNT(*) FROM graph_nodes")
    int countNodesSync();

    @Query("SELECT * FROM graph_nodes WHERE node_id = :nodeId LIMIT 1")
    GraphNodeEntity getNodeByIdSync(long nodeId);

    @Query("SELECT * FROM graph_nodes " +
            "WHERE latitude BETWEEN :minLat AND :maxLat " +
            "AND longitude BETWEEN :minLng AND :maxLng")
    List<GraphNodeEntity> getNodesInBoundingBoxSync(
            double minLat,
            double maxLat,
            double minLng,
            double maxLng
    );

    @Query("SELECT e.* FROM graph_edges e " +
            "INNER JOIN graph_nodes n ON e.from_node_id = n.node_id " +
            "WHERE n.latitude BETWEEN :minLat AND :maxLat " +
            "AND n.longitude BETWEEN :minLng AND :maxLng")
    List<GraphEdgeEntity> getEdgesInBoundingBoxSync(
            double minLat,
            double maxLat,
            double minLng,
            double maxLng
    );

    @Query("SELECT e.* FROM graph_edges e " +
            "INNER JOIN graph_nodes n ON e.from_node_id = n.node_id " +
            "WHERE n.latitude BETWEEN :minLat AND :maxLat " +
            "AND n.longitude BETWEEN :minLng AND :maxLng " +
            "AND e.from_node_id < e.to_node_id " +
            "LIMIT :maxSegments")
    List<GraphEdgeEntity> getStreetEdgesInBoxSync(
            double minLat,
            double maxLat,
            double minLng,
            double maxLng,
            int maxSegments
    );

    /**
     * Busca primero en un recuadro espacial indexado alrededor de la coordenada GPS para
     * respuesta sub-milisegundo sin escanear los 186,050 nodos.
     */
    @Query("SELECT * FROM graph_nodes " +
            "WHERE latitude BETWEEN (:lat - :delta) AND (:lat + :delta) " +
            "AND longitude BETWEEN (:lng - :delta) AND (:lng + :delta) " +
            "ORDER BY ((latitude - :lat) * (latitude - :lat) + (longitude - :lng) * (longitude - :lng)) ASC " +
            "LIMIT 1")
    GraphNodeEntity findNearestNodeInWindowSync(double lat, double lng, double delta);

    /**
     * Encuentra el nodo del grafo más cercano a una coordenada GPS mediante aproximación euclidiana
     * directa en SQLite para tiempos de respuesta sub-milisegundo cuando el agente se desvía.
     */
    @Query("SELECT * FROM graph_nodes " +
            "ORDER BY ((latitude - :lat) * (latitude - :lat) + (longitude - :lng) * (longitude - :lng)) ASC " +
            "LIMIT 1")
    GraphNodeEntity findNearestNodeSync(double lat, double lng);
}
