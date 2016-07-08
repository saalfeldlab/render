package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileIdPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for calculating neighbor pairs for all tiles in a range of sections.
 *
 * @author Eric Trautman
 */
public class TilePairClient {

    @SuppressWarnings("ALL")
    public static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Stack name", required = true)
        private String stack;

        @Parameter(names = "--minZ", description = "Minimum Z value for all tiles", required = true)
        private Double minZ;

        @Parameter(names = "--maxZ", description = "Maximum Z value for all tiles", required = true)
        private Double maxZ;

        @Parameter(
                names = "--xyNeighborFactor",
                description = "Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles (default is 1.3)",
                required = false)
        private Double xyNeighborFactor = 1.3;

        @Parameter(
                names = "--zNeighborDistance",
                description = "Look for neighbor tiles with z values less than or equal to this distance from the current tile's z value (default is 4)",
                required = false)
        private Integer zNeighborDistance = 4;

        @Parameter(names = "--toJson", description = "JSON file where tile pairs are to be stored (.json, .gz, or .zip)", required = true)
        private String toJson;

        @Parameter(names = "--minX", description = "Minimum X value for all tiles", required = false)
        private Double minX;

        @Parameter(names = "--maxX", description = "Maximum X value for all tiles", required = false)
        private Double maxX;

        @Parameter(names = "--minY", description = "Minimum Y value for all tiles", required = false)
        private Double minY;

        @Parameter(names = "--maxY", description = "Maximum Y value for all tiles", required = false)
        private Double maxY;

        public Parameters() {
        }

        public Parameters(final String baseDataUrl,
                          final String owner,
                          final String project,
                          final String stack,
                          final Double minZ,
                          final Double maxZ,
                          final Double xyNeighborFactor,
                          final Integer zNeighborDistance,
                          final Double minX,
                          final Double maxX,
                          final Double minY,
                          final Double maxY) {
            this.baseDataUrl = baseDataUrl;
            this.owner = owner;
            this.project = project;
            this.stack = stack;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.xyNeighborFactor = xyNeighborFactor;
            this.zNeighborDistance = zNeighborDistance;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        public void validateStackBounds() throws IllegalArgumentException {

            if (minZ > maxZ) {
                throw new IllegalArgumentException("minZ (" + minZ + ") is greater than maxX (" + maxZ + ")");
            }

            if ((minX != null) || (maxX != null) || (minY != null) || (maxY != null)) {

                if ((minX == null) || (maxX == null) || (minY == null) || (maxY == null)) {
                    throw new IllegalArgumentException("since one or more of minX (" + minX + "), maxX (" + maxX +
                                                       "), minY (" + minY + "), maxY (" + maxY +
                                                       ") is specified, all must be specified");
                }

                if (minX > maxX) {
                    throw new IllegalArgumentException("minX (" + minX + ") is greater than maxX (" + maxX + ")");
                }

                if (minY > maxY) {
                    throw new IllegalArgumentException("minY (" + minY + ") is greater than maxY (" + maxY + ")");
                }
            }

        }
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, TilePairClient.class);

                File toFile = new File(parameters.toJson).getAbsoluteFile();
                if (! toFile.exists()) {
                    toFile = toFile.getParentFile();
                }

                if (! toFile.canWrite()) {
                    throw new IllegalArgumentException("cannot write to " + toFile.getAbsolutePath());
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final TilePairClient client = new TilePairClient(parameters);
                final Set<TileIdPair> neighborPairs = client.getNeighborPairs();
                FileUtil.saveJsonFile(parameters.toJson, neighborPairs);

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final boolean filterTilesWithBox;
    private final RenderDataClient renderDataClient;

    public TilePairClient(final Parameters parameters) throws IllegalArgumentException {

        parameters.validateStackBounds();

        this.parameters = parameters;
        this.filterTilesWithBox = (parameters.minX != null);

        this.renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                     parameters.owner,
                                                     parameters.project);
    }

    public Set<TileIdPair> getNeighborPairs()
            throws IOException, InterruptedException {

        LOG.info("getNeighborPairs: entry");

        final List<Double> zValues = renderDataClient.getStackZValues(parameters.stack,
                                                                      parameters.minZ,
                                                                      parameters.maxZ);

        final Map<Double, TileBoundsRTree> zToTreeMap = new LinkedHashMap<>();

        long totalTileCount = 0;
        for (final Double z : zValues) {

            List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(parameters.stack, z);
            TileBoundsRTree tree = new TileBoundsRTree(tileBoundsList);

            if (filterTilesWithBox) {
                tileBoundsList = tree.findTilesInBox(parameters.minX,
                                                     parameters.minY,
                                                     parameters.maxX,
                                                     parameters.maxY);
                tree = new TileBoundsRTree(tileBoundsList);
            }

            zToTreeMap.put(z, tree);
            totalTileCount += tileBoundsList.size();
        }

        LOG.info("getNeighborPairs: added bounds for {} tiles to {} trees", totalTileCount, zToTreeMap.size());

        final Set<TileIdPair> neighborPairs = new TreeSet<>();

        Double z;
        Double neighborZ;
        TileBoundsRTree currentZTree;
        List<TileBoundsRTree> neighborTreeList;
        for (int zIndex = 0; zIndex < zValues.size(); zIndex++) {

            z = zValues.get(zIndex);
            currentZTree = zToTreeMap.get(z);

            neighborTreeList = new ArrayList<>();

            final double maxNeighborZ = Math.min(parameters.maxZ, z + parameters.zNeighborDistance);

            for (int neighborZIndex = zIndex + 1; neighborZIndex < zValues.size(); neighborZIndex++) {
                neighborZ = zValues.get(neighborZIndex);
                if (neighborZ > maxNeighborZ) {
                    break;
                }
                neighborTreeList.add(zToTreeMap.get(neighborZ));
            }

            neighborPairs.addAll(currentZTree.getCircleNeighborTileIdPairs(neighborTreeList,
                                                                           parameters.xyNeighborFactor));
        }

        LOG.info("getNeighborPairs: exit, returning {} pairs", neighborPairs.size());

        return neighborPairs;
    }

    private static final Logger LOG = LoggerFactory.getLogger(TilePairClient.class);
}
