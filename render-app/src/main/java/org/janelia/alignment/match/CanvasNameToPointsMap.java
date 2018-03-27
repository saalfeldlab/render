package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.imglib2.RealPoint;

/**
 * Map of canvas names to points.
 *
 * @author Eric Trautman
 */
public class CanvasNameToPointsMap {

    private final Map<String, List<RealPoint>> nameToPoints;
    private final Double scale;

    /**
     * Constructs a map without scaled points.
     */
    public CanvasNameToPointsMap() {
        this(null);
    }

    /**
     * Constructs a map with scaled points.
     *
     * @param  scale  scale factor to apply to all points in the map
     *                (specify null to skip scaling).
     */
    public CanvasNameToPointsMap(final Double scale) {
        this.nameToPoints = new HashMap<>();
        this.scale = scale;
    }

    public Set<String> getNames() {
        return nameToPoints.keySet();
    }

    public List<RealPoint> getPoints(final String forName) {
        return nameToPoints.get(forName);
    }

    /**
     * Loops through specified pairs and maps scaled points for the specified group.
     *
     * @param  groupId     desired group.
     * @param  matchPairs  pairs with match points.
     */
    public void addPointsForGroup(final String groupId,
                                  final Collection<CanvasMatches> matchPairs) {

        List<RealPoint> matchPoints;
        List<RealPoint> points;
        List<RealPoint> savedPoints;

        for (final CanvasMatches pair : matchPairs) {

            final String name;
            if (groupId.equals(pair.getpGroupId())) {
                name = pair.getpId();
                matchPoints = pair.getMatches().getPList();
            } else if (groupId.equals(pair.getqGroupId())) {
                name = pair.getqId();
                matchPoints = pair.getMatches().getQList();
            } else {
                continue;
            }

            if (scale == null) {
                points = matchPoints;
            } else {
                points = new ArrayList<>();
                //noinspection Convert2streamapi
                for (final RealPoint matchPoint : matchPoints) {
                    points.add(new RealPoint(matchPoint.getDoublePosition(0) * scale,
                                             matchPoint.getDoublePosition(1) * scale));
                }
            }

            savedPoints = nameToPoints.get(name);
            if (savedPoints == null) {
                nameToPoints.put(name, points);
            } else {
                savedPoints.addAll(points);
            }
        }

    }
}
