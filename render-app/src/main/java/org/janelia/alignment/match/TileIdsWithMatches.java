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

    /**
     *
     * @param  canvasMatchesList  list of matches for section (could include tiles not in stack).
     * @param  stackTileIds       set of tile ids in stack.
     *                            To be kept, match pair must have both tiles in stack.
     */
    public void addMatches(final List<CanvasMatches> canvasMatchesList,
                           final Set<String> stackTileIds) {
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            final String pId = canvasMatches.getpId();
            final String qId = canvasMatches.getqId();
            if (stackTileIds.contains(pId) && stackTileIds.contains(qId)) {
                this.canvasMatchesList.add(canvasMatches);
                this.tileIds.add(pId);
                this.tileIds.add(qId);
            }
        }
    }

    public boolean contains(final String tileId) {
        return tileIds.contains(tileId);
    }

    public List<CanvasMatches> getCanvasMatchesList() {
        return canvasMatchesList;
    }
}
