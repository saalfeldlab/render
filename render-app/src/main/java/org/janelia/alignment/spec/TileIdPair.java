package org.janelia.alignment.spec;

import com.google.common.base.Objects;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * Pair of tile ids with normalized ordering.
 *
 * @author Eric Trautman
 */
public class TileIdPair implements Comparable<TileIdPair> {

    private final String pId;
    private final String qId;

    public TileIdPair(final String pId,
                      final String qId) {
        final int comparisonResult = pId.compareTo(qId);
        if (comparisonResult == 0) {
            throw new IllegalArgumentException("both IDs are the same: '" + pId + "'");
        } else if (pId.compareTo(qId) > 0) {
            this.qId = pId;
            this.pId = qId;
        } else {
            this.pId = pId;
            this.qId = qId;
        }
    }

    public String getPId() {
        return pId;
    }

    public String getQId() {
        return qId;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TileIdPair that = (TileIdPair) o;
        return Objects.equal(pId, that.pId) &&
               Objects.equal(qId, that.qId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(pId, qId);
    }

    @Override
    public int compareTo(@Nonnull final TileIdPair that) {
        int result = this.pId.compareTo(that.pId);
        if (result == 0) {
            result = this.qId.compareTo(that.qId);
        }
        return result;
    }

    @Override
    public String toString() {
        return "[\"" + pId + "\", \"" + qId + "\"]";
    }

    /**
     * @return set containing the paired tileIds of the fromTile with each toTile.
     *         If the fromTile is in the toTiles list, it is ignored (fromTile won't be paired with itself).
     */
    public static Set<TileIdPair> getTileIdPairs(final TileBounds fromTile,
                                                 final List<TileBounds> toTiles) {
        final Set<TileIdPair> comboSet = new HashSet<>(toTiles.size() + 1);
        final String pId = fromTile.getTileId();
        String qId;
        for (final TileBounds toTile : toTiles) {
            qId = toTile.getTileId();
            if (! pId.equals(qId)) {
                comboSet.add(new TileIdPair(pId, qId));
            }
        }
        return comboSet;
    }

}
