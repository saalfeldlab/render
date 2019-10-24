package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.match.ClusterOverlapProblem;
import org.janelia.alignment.match.SortedConnectedCanvasIdClusters;
import org.janelia.alignment.match.TileIdsWithMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for ...
 *
 * @author Eric Trautman
 */
public class ClusterOverlapClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @ParametersDelegate
        TileClusterParameters tileCluster = new TileClusterParameters();

        @Parameter(
                names = "--renderIntersectingClusters",
                description = "Render any cluster intersection areas for review",
                arity = 0)
        public boolean renderIntersectingClusters = false;

        @Parameter(
                names = "--saveProblemJson",
                description = "Save JSON file containing bounds information for overlapping tiles",
                arity = 0)
        public boolean saveProblemJson = false;

        @Parameter(
                names = "--rootOutputDirectory",
                description = "Root directory for cluster intersection images and/or problem JSON data")
        public String rootOutputDirectory = ".";

        @Parameter(
                names = "--z",
                description = "Explicit z values for sections to be processed",
                required = true,
                variableArity = true) // e.g. --z 20.0 21.0 22.0
        public List<Double> zValues;
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                parameters.tileCluster.validate(true);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ClusterOverlapClient client = new ClusterOverlapClient(parameters);
                client.checkLayers();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private ClusterOverlapClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private void checkLayers()
            throws Exception {

        final RenderDataClient renderDataClient = parameters.renderWeb.getDataClient();
        final RenderWebServiceUrls renderWebServiceUrls = renderDataClient.getUrls();

        final RenderDataClient matchDataClient =
                parameters.tileCluster.getMatchDataClient(parameters.renderWeb.baseDataUrl,
                                                          parameters.renderWeb.owner);

        for (final Double z : parameters.zValues) {

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            final Set<String> stackTileIds = new HashSet<>(resolvedTiles.getTileIds());

            final TileIdsWithMatches tileIdsWithMatches =
                    UnconnectedTileRemovalClient.getTileIdsWithMatches(renderDataClient,
                                                                       parameters.stack,
                                                                       z,
                                                                       matchDataClient,
                                                                       stackTileIds,
                                                                       parameters.tileCluster.includeMatchesOutsideGroup);

            final SortedConnectedCanvasIdClusters clusters =
                    new SortedConnectedCanvasIdClusters(tileIdsWithMatches.getCanvasMatchesList());
            final List<Set<String>> sortedConnectedTileIdSets = clusters.getSortedConnectedTileIdSets();

            LOG.info("checkLayers: for z {}, found {} connected tile sets with sizes {}",
                     z, clusters.size(), clusters.getClusterSizes());

            final List<List<TileBounds>> clusterBoundsLists = getClusterBoundsLists(resolvedTiles,
                                                                                    sortedConnectedTileIdSets);

            final List<ClusterOverlapProblem> overlapProblems =
                    ClusterOverlapProblem.findOverlapProblems(z, clusterBoundsLists);

            if (overlapProblems.size() > 0) {
                overlapProblems.forEach(ClusterOverlapProblem::logProblemDetails);

                if (parameters.renderIntersectingClusters) {
                    final File imageDir = getBatchImageDirectory(z);
                    overlapProblems.forEach(op -> op.render(renderWebServiceUrls, parameters.stack, imageDir));
                }

                if (parameters.saveProblemJson) {
                    final File imageDir = getBatchImageDirectory(z);
                    saveProblemJson(overlapProblems, imageDir, z);
                }

            } else {
                LOG.info("checkLayers: no overlap problems found for z {}", z);
            }

        }

    }

    /**
     * @return list of tile bounds lists for each cluster with at least one tile in the resolved tiles set.
     */
    private List<List<TileBounds>> getClusterBoundsLists(final ResolvedTileSpecCollection resolvedTiles,
                                                         final List<Set<String>> sortedConnectedTileIdSets) {
        final List<List<TileBounds>> clusterBoundsLists = new ArrayList<>();

        for (final Set<String> clusterTileIds : sortedConnectedTileIdSets) {
            final List<TileBounds> clusterBoundsList = new ArrayList<>(clusterTileIds.size());
            for (final String tileId : clusterTileIds) {
                final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);
                if (tileSpec != null) {
                    clusterBoundsList.add(tileSpec.toTileBounds());
                }
            }
            if (clusterBoundsList.size() > 0) {
                clusterBoundsLists.add(clusterBoundsList);
            }
        }
        return clusterBoundsLists;
    }

    private void saveProblemJson(final List<ClusterOverlapProblem> overlapProblems,
                                 final File imageDir,
                                 final Double z)
            throws IOException {

        final String jsonFileName = String.format("problem_overlap_bounds_%s_z%06.0f.json", parameters.stack, z);
        final Path path = Paths.get(imageDir.getAbsolutePath(), jsonFileName);

        final StringBuilder json = new StringBuilder();
        json.append("[\n");
        for (final ClusterOverlapProblem problem : overlapProblems) {
            if (json.length() > 2) {
                json.append(",\n");
            }
            json.append(problem.getBounds().toJson());
        }
        json.append("\n]");

        Files.write(path, json.toString().getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);

        LOG.info("saveProblemJson: saved {}", path);
    }

    private File getBatchImageDirectory(final Double z) {
        return FileUtil.createBatchedZDirectory(parameters.rootOutputDirectory,
                                                "problem_overlap_batch_",
                                                z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ClusterOverlapClient.class);
}
