package com.example.georutassos.data.local.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Entidad Room para las aristas dirigidas y ponderadas del grafo vial local.
 */
@Entity(
        tableName = "graph_edges",
        indices = {
                @Index(value = {"from_node_id"}),
                @Index(value = {"to_node_id"})
        }
)
public class GraphEdgeEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "edge_id")
    private long edgeId;

    @ColumnInfo(name = "from_node_id")
    private long fromNodeId;

    @ColumnInfo(name = "to_node_id")
    private long toNodeId;

    /** Peso de la arista (distancia en metros o costo de tiempo estimado). */
    @ColumnInfo(name = "weight")
    private double weight;

    public GraphEdgeEntity(long fromNodeId, long toNodeId, double weight) {
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.weight = weight;
    }

    public long getEdgeId() {
        return edgeId;
    }

    public void setEdgeId(long edgeId) {
        this.edgeId = edgeId;
    }

    public long getFromNodeId() {
        return fromNodeId;
    }

    public void setFromNodeId(long fromNodeId) {
        this.fromNodeId = fromNodeId;
    }

    public long getToNodeId() {
        return toNodeId;
    }

    public void setToNodeId(long toNodeId) {
        this.toNodeId = toNodeId;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }
}
