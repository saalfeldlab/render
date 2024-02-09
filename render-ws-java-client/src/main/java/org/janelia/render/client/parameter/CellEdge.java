package org.janelia.render.client.parameter;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;

/**
 * Identifies an edge between two cells of a grid.
 *
 * @author Eric Trautman
 */
public class CellEdge
        implements Comparable<CellEdge> {
    private final CellId fromCell;
    private final CellId toCell;

    public CellEdge(final CellId cellA,
                    final CellId cellB) {
        // need to normalize edge ordering
        if (cellA.row == cellB.row) {

            if (cellA.column < cellB.column) {
                this.fromCell = cellA;
                this.toCell = cellB;
            } else if (cellA.column > cellB.column) {
                this.fromCell = cellB;
                this.toCell = cellA;
            } else {
                throw new IllegalArgumentException("tile edge cannot have both same row and same column");
            }

            final int delta = this.toCell.column - this.fromCell.column;
            if (delta != 1) {
                throw new IllegalArgumentException("tile edge cannot have column delta " + delta);
            }

        } else if (cellA.column == cellB.column) {

            if (cellA.row < cellB.row) {
                this.fromCell = cellA;
                this.toCell = cellB;
            } else {
                this.fromCell = cellB;
                this.toCell = cellA;
            }

            final int delta = this.toCell.row - this.fromCell.row;
            if (delta != 1) {
                throw new IllegalArgumentException("tile edge cannot have row delta " + delta);
            }

        } else {
            throw new IllegalArgumentException("tile edge must have either same row or same column");
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final CellEdge cellEdge = (CellEdge) o;

        if (!fromCell.equals(cellEdge.fromCell)) {
            return false;
        }
        return toCell.equals(cellEdge.toCell);
    }

    @Override
    public int hashCode() {
        int result = fromCell.hashCode();
        result = 31 * result + toCell.hashCode();
        return result;
    }

    @Override
    public int compareTo(final CellEdge that) {
        int result = this.fromCell.compareTo(that.fromCell);
        if (result == 0) {
            result = this.toCell.compareTo(that.toCell);
        }
        return result;
    }

    @Override
    public String toString() {
        return "{\"from\": " + fromCell +
               ", \"to\": " + toCell +
               '}';

    }

    public static CellEdge forPair(final String pId,
                                   final String qId,
                                   final ResolvedTileSpecCollection resolvedTiles) {
        CellEdge edge = null;
        final CellId pCell = CellId.fromTileSpec(resolvedTiles.getTileSpec(pId));
        final CellId qCell = CellId.fromTileSpec(resolvedTiles.getTileSpec(qId));
        if ((pCell != null) && (qCell != null)) {
            edge = new CellEdge(pCell, qCell);
        }
        return edge;
    }
}