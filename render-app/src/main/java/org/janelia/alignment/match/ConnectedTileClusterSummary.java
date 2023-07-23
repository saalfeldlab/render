package org.janelia.alignment.match;

import java.io.Serializable;

/**
 * Summary information about one connected tile cluster in a stack.
 *
 * @author Eric Trautman
 */
public class ConnectedTileClusterSummary
        implements Serializable {

    private final int tileCount;
    private final String firstTileId;
    private final String lastTileId;

    public ConnectedTileClusterSummary(final int tileCount,
                                       final String firstTileId,
                                       final String lastTileId) {
        this.tileCount = tileCount;
        this.firstTileId = firstTileId;
        this.lastTileId = lastTileId;
    }

    public ConnectedTileClusterSummary(final int tileCount,
                                       final String anyTileId) {
        this.tileCount = tileCount;
        this.firstTileId = anyTileId;
        this.lastTileId = null;
    }

    @Override
    public String toString() {
        final String tileIdInfo;
        if (firstTileId != null) {
            if (lastTileId != null) {
                tileIdInfo = "with first tile " + firstTileId + " and last tile " + lastTileId;
            } else {
                tileIdInfo = "including tile " + firstTileId;
            }
        } else {
            tileIdInfo = "";
        }
        return tileCount + " tile set " + tileIdInfo;
    }
}
