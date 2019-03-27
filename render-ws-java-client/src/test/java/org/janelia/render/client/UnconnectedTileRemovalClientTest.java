package org.janelia.render.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link UnconnectedTileRemovalClient} class.
 *
 * @author Eric Trautman
 */
public class UnconnectedTileRemovalClientTest {

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage",
                "--stack", "v15_montage_check_1503",
                "--z", "1503",
                "--matchCollection", "FAFB_montage_fix",
                "--maxSmallClusterSize", "1",
                "--reportRemovedTiles"
        };

        UnconnectedTileRemovalClient.main(effectiveArgs);

    }

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new UnconnectedTileRemovalClient.Parameters());
    }

    @Test
    public void testMarkSmallClustersAsUnconnected() {
        final List<CanvasMatches> matchesList = new ArrayList<>();
        final Set<String> unconnectedTileIds = new HashSet<>();

        //   A-B-C-D   O-P
        //   | | | |
        //   E-F-G-H   Q-R-S
        //             | | |
        //   I-J       T-U-V
        //
        //   K-L-M-N   X-Y

        final String[][] testData = {
                {"A","B"},{"B","C"},{"C","D"},{"A","E"},{"B","F"},{"C","G"},{"D","H"},{"E","F"},{"F","G"},{"G","H"},
                {"I","J"},
                {"K","L"},{"L","M"},{"M","N"},
                {"O","P"},
                {"Q","R"},{"R","S"},{"Q","T"},{"R","U"},{"S","V"},{"T","U"},{"U","V"},
                {"X","Y"}
        };

        final String g = "group";
        final double[][] p = {{8.0},{8.0}};
        final double[][] q = {{9.0},{9.0}};
        final double[] w = {1.0};
        final Matches m = new Matches(p, q, w);

        for (final String[] pair : testData) {
            matchesList.add(new CanvasMatches(g, pair[0], g, pair[1], m));
        }

        final UnconnectedTileRemovalClient.Parameters parameters = new UnconnectedTileRemovalClient.Parameters();
        parameters.tileCluster.smallClusterFactor = 0.5; // should result in maxSmallClusterSize of 4 (0.5 * 8)

        final Double z = 99.0;
        final SortedConnectedCanvasIdClusters clusters = new SortedConnectedCanvasIdClusters(matchesList);
        final List<Set<String>> sortedConnectedTileSets = clusters.getSortedConnectedTileIdSets();

        final Set<String> keeperTileIds = new HashSet<>();
        List<Set<String>> smallerRemainingClusters =
                UnconnectedTileRemovalClient.markSmallClustersAsUnconnected(parameters.tileCluster,
                                                                            z,
                                                                            sortedConnectedTileSets,
                                                                            keeperTileIds,
                                                                            unconnectedTileIds);

        final String[] expectedUnconnectedTiles = {"I", "J", "K", "L", "M", "N", "O", "P", "X", "Y"};
        Assert.assertEquals("invalid number of small cluster tiles found ",
                            expectedUnconnectedTiles.length, unconnectedTileIds.size());

        for (final String tileId : expectedUnconnectedTiles) {
            Assert.assertTrue("tileId " + tileId + " should have been marked as unconnected",
                              unconnectedTileIds.contains(tileId));
        }

        Assert.assertEquals("invalid number of smaller remaining clusters returned",
                            1, smallerRemainingClusters.size());

        // all-inclusive test
        parameters.tileCluster.maxSmallClusterSize = 1;

        smallerRemainingClusters =
                UnconnectedTileRemovalClient.markSmallClustersAsUnconnected(parameters.tileCluster,
                                                                            z,
                                                                            sortedConnectedTileSets,
                                                                            keeperTileIds,
                                                                            unconnectedTileIds);

        Assert.assertEquals("all inclusive test: invalid number of smaller remaining clusters returned",
                            5, smallerRemainingClusters.size());

    }

}
