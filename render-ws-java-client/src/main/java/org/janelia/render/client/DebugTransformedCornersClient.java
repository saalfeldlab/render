package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.Point;

import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for debugging transformed corners of IBEAM-MSEM stacks.
 *
 * @author Eric Trautman
 */
public class DebugTransformedCornersClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stacks to process",
                variableArity = true,
                required = true)
        public List<String> stackNames;

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--z",
                description = "Explicit z values for layers to be processed",
                variableArity = true) // e.g. --z 20.0 --z 21.0 --z 22.0
        public List<Double> zValues;

        @Parameter(
                names = "--xyNeighborFactor",
                description = "Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles",
                required = true
        )
        public Double xyNeighborFactor;

        @Parameter(
                names = "--tileId",
                description = "Only debug pairs that include these tileIds",
                variableArity = true
        )
        public List<String> tileIds;

        public Parameters() {
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final DebugTransformedCornersClient client = new DebugTransformedCornersClient(parameters);
                client.debugPairs();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final List<Double> zValues;
    private final Set<String> tileIds;

    DebugTransformedCornersClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        final String firstStackName = parameters.stackNames.get(0);
        this.zValues = renderDataClient.getStackZValues(firstStackName,
                                                        parameters.layerRange.minZ,
                                                        parameters.layerRange.maxZ,
                                                        parameters.zValues);
        if (this.zValues.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + firstStackName + " does not contain any layers with the specified z values");
        }
        Collections.sort(this.zValues);

        if ((parameters.tileIds != null) && (parameters.tileIds.size() > 0)) {
            this.tileIds = new HashSet<>(parameters.tileIds);
        } else {
            this.tileIds = null;
        }
    }

    private void debugPairs()
            throws IOException {
        final List<String> debugInfo = new ArrayList<>();
        for (final String stackName : parameters.stackNames) {
            for (final Double z : zValues) {
                debugInfo.addAll(debugPairsForZ(stackName, z));
            }
        }
        LOG.info("debug results are:");
        debugInfo.stream().sorted().forEach(System.out::println);
    }

    private List<String> debugPairsForZ(final String stackName,
                                        final Double z)
            throws IOException {

        LOG.info("debugPairsForZ: entry, stackName={}, z={}", stackName, z);

        final List<String> debugInfo = new ArrayList<>();

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stackName, z);

        final List<TileBounds> tileBoundsList =
                resolvedTiles.getTileSpecs().stream().map(TileSpec::toTileBounds).collect(Collectors.toList());
        final TileBoundsRTree tree = new TileBoundsRTree(z, tileBoundsList);

        final Set<OrderedCanvasIdPair> neighborPairs = tree.getCircleNeighbors(tileBoundsList,
                                                                               new ArrayList<>(),
                                                                               parameters.xyNeighborFactor,
                                                                               null,
                                                                               false,
                                                                               false,
                                                                               false);
        neighborPairs.stream().sorted().forEach(pair -> {
            final TileSpec pTileSpec = resolvedTiles.getTileSpec(pair.getP().getId());
            final TileSpec qTileSpec = resolvedTiles.getTileSpec(pair.getQ().getId());
            if (tileIds == null ||
                tileIds.contains(pTileSpec.getTileId()) ||
                tileIds.contains(qTileSpec.getTileId())) {
                debugInfo.add(formatCornerPointDistances(stackName, pTileSpec, qTileSpec));
            }
        });

        return debugInfo;
    }

    public static List<Point> getTransformedCornerPoints(final TileSpec tileSpec) {

        final List<Point> transformedCornerPoints = new ArrayList<>();

        final double[][] rawCornerLocations = {
            {                   0,                    0 },
            { tileSpec.getWidth(),                    0 },
            {                   0, tileSpec.getHeight() },
            { tileSpec.getWidth(), tileSpec.getHeight() }
        };

        final CoordinateTransformList<CoordinateTransform> transformList = tileSpec.getTransformList();
        for (final double[] rawCornerLocation : rawCornerLocations)  {
            transformedCornerPoints.add(new Point(transformList.apply(rawCornerLocation)));
        }

        return transformedCornerPoints;
    }

    public static String formatCornerPointDistances(final String stack,
                                                    final TileSpec pTileSpec,
                                                    final TileSpec qTileSpec) {

        final List<Point> pTransformedCorners = getTransformedCornerPoints(pTileSpec);
        final List<Point> qTransformedCorners = getTransformedCornerPoints(qTileSpec);

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pTransformedCorners.size(); i++) {
            final double distance = Point.distance(pTransformedCorners.get(i), qTransformedCorners.get(i));
            sb.append(String.format("%8.1f", distance));
        }

        return String.format("%40s to %40s in %-40s= %s", pTileSpec.getTileId(), qTileSpec.getTileId(), stack, sb);
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(DebugTransformedCornersClient.class);
}
