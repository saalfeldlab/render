package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for straightening a stack by gradually applying an offset
 * computed from the midpoints of the first and last layer bounding boxes.
 * <br>
 * The offset is linearly interpolated across layers:
 * layer z is moved by (z - zmin) / (zmax - zmin) * offset
 */
public class StackStraighteningClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Name of source stack",
                required = true)
        public String stack;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack",
                required = true)
        public String targetStack;

        @Parameter(
                names = "--numberOfZLayersPerChunk",
                description = "Number of Z layers to process per chunk")
        public int numberOfZLayersPerChunk = 1000;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final StackStraighteningClient client = new StackStraighteningClient(parameters);
                client.straightenStack();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final List<Double> zValues;
    private final double minZ;
    private final double maxZ;
    private final double zRange;

    private StackStraighteningClient(final Parameters parameters)
            throws IOException {

        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        this.zValues = renderDataClient.getStackZValues(parameters.stack);

        if (zValues.size() < 2) {
            throw new IllegalArgumentException("Stack must have at least 2 layers for straightening");
        }

        this.minZ = zValues.get(0);
        this.maxZ = zValues.get(zValues.size() - 1);
        this.zRange = maxZ - minZ;

        if (parameters.numberOfZLayersPerChunk < 1) {
            throw new IllegalArgumentException("numberOfZLayersPerChunk must be at least 1");
        }
    }

    private void straightenStack() throws Exception {

        final StackMetaData sourceStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        renderDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);

        LOG.info("straightenStack: minZ={}, maxZ={}", minZ, maxZ);

        // Get bounding box midpoints for first and last layers
        final double[] firstLayerMidpoint = getLayerBoundingBoxMidpoint(minZ);
        final double[] lastLayerMidpoint = getLayerBoundingBoxMidpoint(maxZ);

        // Compute the total offset between first and last layer midpoints
        final double totalOffsetX = lastLayerMidpoint[0] - firstLayerMidpoint[0];
        final double totalOffsetY = lastLayerMidpoint[1] - firstLayerMidpoint[1];

        LOG.info("straightenStack: firstLayerMidpoint=({}, {}), lastLayerMidpoint=({}, {}), totalOffset=({}, {})",
                 firstLayerMidpoint[0], firstLayerMidpoint[1],
                 lastLayerMidpoint[0], lastLayerMidpoint[1],
                 totalOffsetX, totalOffsetY);

        for (int start = 0; start < zValues.size(); start += parameters.numberOfZLayersPerChunk) {
            final int end = Math.min(start + parameters.numberOfZLayersPerChunk, zValues.size());
            final List<Double> zLayersChunk = zValues.subList(start, end);
            straightenLayers(zLayersChunk, totalOffsetX, totalOffsetY);
        }

        renderDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);

        LOG.info("straightenStack: exit, processed {} layers", zValues.size());
    }

    private double[] getLayerBoundingBoxMidpoint(final double z) throws Exception {
        final Bounds layerBounds = renderDataClient.getLayerBounds(parameters.stack, z);

        if (layerBounds == null || layerBounds.getMinX() == null) {
            throw new IllegalArgumentException("Cannot compute bounding box for layer z=" + z);
        }

        final double midX = (layerBounds.getMinX() + layerBounds.getMaxX()) / 2.0;
        final double midY = (layerBounds.getMinY() + layerBounds.getMaxY()) / 2.0;

        LOG.info("getLayerBoundingBoxMidpoint: z={}, bounds={}, midpoint=({}, {})",
                 z, layerBounds, midX, midY);

        return new double[] { midX, midY };
    }

    private void straightenLayers(final List<Double> zLayersChunk,
                                  final double totalOffsetX,
                                  final double totalOffsetY)
            throws Exception {

        final Double firstZ = zLayersChunk.get(0);
        final Double lastZ = zLayersChunk.get(zLayersChunk.size() - 1);

        LOG.info("straightenLayers: entry, firstZ={}, lastZ={}", firstZ, lastZ);

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack,
                                                                                           firstZ,
                                                                                           lastZ,
                                                                                           null,
                                                                                           null,
                                                                                           null,
                                                                                           null,
                                                                                           null,
                                                                                           null);

        final Map<Double, List<TileSpec>> zToTileSpecs = new HashMap<>();
        final Map<Double, LeafTransformSpec> zToTranslation = new HashMap<>();

        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {

            final List<TileSpec> tileSpecsForZ = zToTileSpecs.computeIfAbsent(tileSpec.getZ(),
                                                                              k -> new ArrayList<>());
            tileSpecsForZ.add(tileSpec);

            if (! zToTranslation.containsKey(tileSpec.getZ())) {

                final Double z = tileSpec.getZ();

                // Compute the interpolation factor for this layer
                // At zMin, factor = 0 (no movement)
                // At zMax, factor = 1 (full offset applied, but negated to bring it back to first layer position)
                final double factor = (z - minZ) / zRange;

                // The translation needed to straighten this layer
                final double translateX = -factor * totalOffsetX;
                final double translateY = -factor * totalOffsetY;

                final AffineModel2D translationModel = new AffineModel2D();
                translationModel.set(1, 0, 0, 1, translateX, translateY);

                zToTranslation.put(z, new LeafTransformSpec(translationModel.getClass().getName(),
                                                            translationModel.toDataString()));
            }
        }

        for (final Double z : zLayersChunk) {

            final List<TileSpec> layerTileSpecs = zToTileSpecs.get(z);
            final LeafTransformSpec transformSpec = zToTranslation.get(z);

            for (final TileSpec tileSpec : layerTileSpecs) {
                resolvedTiles.addTransformSpecToTile(tileSpec.getTileId(),
                                                     transformSpec,
                                                     ResolvedTileSpecCollection.TransformApplicationMethod.PRE_CONCATENATE_LAST);
            }

            LOG.debug("straightenLayers: applied affine transform {} to {} tiles for z={}",
                      transformSpec.getDataString(), layerTileSpecs.size(), z);
        }

        renderDataClient.saveResolvedTiles(resolvedTiles, parameters.targetStack, null);

        LOG.info("straightenLayers: exit, saved {} tiles for z {} to {}", resolvedTiles.getTileCount(), firstZ, lastZ);
    }

    private static final Logger LOG = LoggerFactory.getLogger(StackStraighteningClient.class);
}
