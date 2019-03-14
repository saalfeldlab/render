package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * List of {@link CanvasMatches} with associated tileIds mapped for fast lookup.
 *
 * @author Eric Trautman
 */
public class TileIdsWithMatches {

    private final Set<String> tileIds;
    private final List<CanvasMatches> canvasMatchesList;

    public TileIdsWithMatches() {
        this.canvasMatchesList = new ArrayList<>();
        this.tileIds = new HashSet<>();
    }

    public void addMatches(final List<CanvasMatches> canvasMatchesList) {
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            this.canvasMatchesList.add(canvasMatches);
            this.tileIds.add(canvasMatches.getpId());
            this.tileIds.add(canvasMatches.getqId());
        }
    }

    public boolean contains(final String tileId) {
        return tileIds.contains(tileId);
    }

    public List<CanvasMatches> getCanvasMatchesList() {
        return canvasMatchesList;
    }
}
