package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parameters and methods for determining clusters of connected tiles within a layer.
 *
 * @author Eric Trautman
 */
public class TileClusterParameters
        implements Serializable {

    @Parameter(
            names = "--matchOwner",
            description = "Match collection owner (default is to use stack owner)")
    public String matchOwner;

    @Parameter(
            names = "--matchCollection",
            description = "Match collection name")
    public String matchCollection;

    @Parameter(
            names = "--maxSmallClusterSize",
            description = "If specified, small connected clusters with this many or fewer tiles will be " +
                          "considered unconnected and be removed.")
    public Integer maxSmallClusterSize;

    @Parameter(
            names = "--smallClusterFactor",
            description = "If specified, relatively small connected clusters will be considered unconnected " +
                          "and be removed.  A layer's max small cluster size is calculated by multiplying this " +
                          "factor by the size of the layer's largest connected cluster.  " +
                          "This value will be ignored if --maxSmallClusterSize is specified.")
    public Double smallClusterFactor;

    public void validate()
            throws IllegalArgumentException {

        if (isDefined()) {

            if (matchCollection == null) {
                throw new IllegalArgumentException(
                        "--matchCollection must be specified when " +
                        "--maxSmallClusterSize or --smallClusterFactor is specified");
            }

        } else if (matchCollection != null) {
            throw new IllegalArgumentException(
                    "--maxSmallClusterSize or --smallClusterFactor must be specified when " +
                    "--matchCollection is specified");
        }

    }

    public boolean isDefined() {
        return (maxSmallClusterSize != null) || (smallClusterFactor != null);
    }

    public int getEffectiveMaxSmallClusterSize(final int largestClusterSize) {
        int maxSize = 0;
        if (isDefined()) {
            maxSize = maxSmallClusterSize == null ?
                      (int) Math.ceil(largestClusterSize * smallClusterFactor) :
                      maxSmallClusterSize;
        }
        return maxSize;
    }

    public RenderDataClient getMatchDataClient(final String baseDataUrl, final String defaultOwner) {
        RenderDataClient client = null;
        if (matchCollection != null) {
            final String owner = matchOwner == null ? defaultOwner : matchOwner;
            client = new RenderDataClient(baseDataUrl, owner, matchCollection);
        }
        return client;
    }

    public static List<Set<String>> buildAndSortConnectedTileSets(final Double z,
                                                                  final List<CanvasMatches> matchesList) {

        final Map<String, Set<String>> connectionsMap = new HashMap<>();

        Set<String> pSet;
        Set<String> qSet;

        for (final CanvasMatches matches : matchesList) {
            final String pId = matches.getpId();
            final String qId = matches.getqId();

            pSet = connectionsMap.computeIfAbsent(pId, k -> new HashSet<>());
            pSet.add(qId);

            qSet = connectionsMap.computeIfAbsent(qId, k -> new HashSet<>());
            qSet.add(pId);
        }

        final List<Set<String>> connectedTileSets = new ArrayList<>();

        while (connectionsMap.size() > 0) {
            @SuppressWarnings("OptionalGetWithoutIsPresent")
            final String tileId = connectionsMap.keySet().stream().findFirst().get();
            final Set<String> connectedTileSet = new HashSet<>();
            addConnectedTiles(tileId, connectionsMap, connectedTileSet);
            connectedTileSets.add(connectedTileSet);
        }

        connectedTileSets.sort(Comparator.comparingInt(Set::size));

        final List<Integer> connectedSetSizes = new ArrayList<>();
        connectedTileSets.forEach(tileIds -> connectedSetSizes.add(tileIds.size()));

        LOG.info("buildAndSortConnectedTileSets: for z {}, found {} connected tile sets with sizes {}",
                 z, connectedTileSets.size(), connectedSetSizes);

        return connectedTileSets;
    }

    private static void addConnectedTiles(final String tileId,
                                          final Map<String, Set<String>> connectionsMap,
                                          final Set<String> connectedTileSet) {

        final boolean isNewConnection = connectedTileSet.add(tileId);

        if (isNewConnection) {

            final Set<String> connectedTileIds = connectionsMap.remove(tileId);

            if (connectedTileIds != null) {
                for (final String connectedTileId : connectedTileIds) {
                    addConnectedTiles(connectedTileId, connectionsMap, connectedTileSet);
                }
            }

        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileClusterParameters.class);

}
