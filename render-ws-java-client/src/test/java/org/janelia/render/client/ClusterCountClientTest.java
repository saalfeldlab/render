package org.janelia.render.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.janelia.alignment.match.ConnectedTileClusterSummaryForStack;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.junit.Test;

/**
 * Tests the {@link ClusterCountClient} class.
 *
 * @author Eric Trautman
 */
public class ClusterCountClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ClusterCountClient.Parameters());
    }

    public static void main(final String[] args)
            throws Exception {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[]{
                "--baseDataUrl", "http://em-services-1:8080/render-ws/v1",
                "--owner", "hess_wafers_60_61",
                "--project", "w61_serial_070_to_079",
                "--stack", "w61_s073_r00_gc_pa_rough",
                "--matchCollection", "w61_s073_r00_gc_par_match",
                "--maxSmallClusterSize", "0",
//                "--includeMatchesOutsideGroup",
                "--maxLayersPerBatch", "1000",
                "--maxOverlapLayers", "6",
//                "--maxSmallClusterSizeToSave", "100"
        };

        ClusterCountClient.main(effectiveArgs);
//        findWafer61Clusters();


    }

    @SuppressWarnings("unused")
    public static void findWafer61Clusters() throws Exception {

        final String baseDataUrl = "http://em-services-1:8080/render-ws/v1";

        final String owner = "hess_wafers_60_61";
        final String project = "w61_serial_100_to_109";
        final String stack = "w61_s108_r00_gc_par";
        final int lastZ = 96;
        final String mcName = stack + "_match";

        final StackId stackId = new StackId(owner, project, stack);
        final List<Double> zValues = new ArrayList<>();
        for (int z = 1; z <= lastZ; z++) {
            zValues.add((double) z);
        }
        final StackWithZValues stackWithZValues = new StackWithZValues(stackId, zValues);

        final MatchCollectionId matchCollectionId = new MatchCollectionId(owner, mcName);

        final ClusterCountClient.Parameters jcccp = new ClusterCountClient.Parameters();
        jcccp.multiProject = MultiProjectParameters.singleStackInstance(baseDataUrl, stackId);
        jcccp.tileCluster = new TileClusterParameters();

        final int zCount = 96;
        jcccp.tileCluster.maxSmallClusterSize = 0;
        jcccp.tileCluster.includeMatchesOutsideGroup = true;
        jcccp.tileCluster.maxLayersPerBatch = zCount + 1;
        jcccp.tileCluster.maxOverlapLayers = 6;

        final RenderDataClient dataClient = new RenderDataClient(baseDataUrl, owner, project);
        final ClusterCountClient javaClusterCountClient = new ClusterCountClient(jcccp);

        final ConnectedTileClusterSummaryForStack summary =
                javaClusterCountClient.findConnectedClustersForStack(stackWithZValues,
                                                                     matchCollectionId,
                                                                     dataClient,
                                                                     jcccp.tileCluster);
        final String countErrorString = summary.buildCountErrorString(1,
                                                                      0,
                                                                      0);
        if (! countErrorString.isEmpty()) {
            throw new IllegalStateException(countErrorString);
        }
    }
}
