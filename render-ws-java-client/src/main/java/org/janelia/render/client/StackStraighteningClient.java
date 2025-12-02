package org.janelia.render.client;

import java.io.IOException;
import java.util.List;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.trakem2.transform.AffineModel2D;

/**
 * Java client for straightening a stack by gradually applying an offset
 * computed from the midpoints of the first and last layer bounding boxes.
 *
 * The offset is linearly interpolated across layers:
 * layer z is moved by (z - zmin) / (zmax - zmin) * offset
 *
 * @author Michael Innerberger
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
                names = "--targetOwner",
                description = "Name of target stack owner (default is same as source stack owner)")
        public String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)")
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack",
                required = true)
        public String targetStack;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after processing all layers",
                arity = 0)
        public boolean completeTargetStack = false;

        public String getTargetOwner() {
            return targetOwner == null ? renderWeb.owner : targetOwner;
        }

        public String getTargetProject() {
            return targetProject == null ? renderWeb.project : targetProject;
        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final StackStraighteningClient client = new StackStraighteningClient(parameters);

                client.setupDerivedStack();
                client.straightenStack();

                if (parameters.completeTargetStack) {
                    client.completeTargetStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient sourceDataClient;
    private final RenderDataClient targetDataClient;
    private final List<Double> zValues;

    private StackStraighteningClient(final Parameters parameters)
            throws IOException {

        this.parameters = parameters;

        this.sourceDataClient = parameters.renderWeb.getDataClient();
        this.targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                     parameters.getTargetOwner(),
                                                     parameters.getTargetProject());

        this.zValues = sourceDataClient.getStackZValues(parameters.stack);

        if (zValues.size() < 2) {
            throw new IllegalArgumentException("Stack must have at least 2 layers for straightening");
        }
    }

    private void setupDerivedStack() throws IOException {
        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
    }

    private void completeTargetStack() throws Exception {
        targetDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
    }

    private void straightenStack() throws Exception {

        final double zMin = zValues.get(0);
        final double zMax = zValues.get(zValues.size() - 1);

        LOG.info("straightenStack: zMin={}, zMax={}", zMin, zMax);

        // Get bounding box midpoints for first and last layers
        final double[] firstLayerMidpoint = getLayerBoundingBoxMidpoint(zMin);
        final double[] lastLayerMidpoint = getLayerBoundingBoxMidpoint(zMax);

        // Compute the total offset between first and last layer midpoints
        final double totalOffsetX = lastLayerMidpoint[0] - firstLayerMidpoint[0];
        final double totalOffsetY = lastLayerMidpoint[1] - firstLayerMidpoint[1];

        LOG.info("straightenStack: firstLayerMidpoint=({}, {}), lastLayerMidpoint=({}, {}), totalOffset=({}, {})",
                 firstLayerMidpoint[0], firstLayerMidpoint[1],
                 lastLayerMidpoint[0], lastLayerMidpoint[1],
                 totalOffsetX, totalOffsetY);

        final double zRange = zMax - zMin;

        // Process each layer
        for (final Double z : zValues) {
            straightenLayer(z, zMin, zRange, totalOffsetX, totalOffsetY);
        }

        LOG.info("straightenStack: exit, processed {} layers", zValues.size());
    }

    private double[] getLayerBoundingBoxMidpoint(final double z) throws Exception {
        final ResolvedTileSpecCollection tiles = sourceDataClient.getResolvedTiles(parameters.stack, z);
        final Bounds layerBounds = tiles.toBounds();

        if (layerBounds == null || layerBounds.getMinX() == null) {
            throw new IllegalArgumentException("Cannot compute bounding box for layer z=" + z);
        }

        final double midX = (layerBounds.getMinX() + layerBounds.getMaxX()) / 2.0;
        final double midY = (layerBounds.getMinY() + layerBounds.getMaxY()) / 2.0;

        LOG.info("getLayerBoundingBoxMidpoint: z={}, bounds={}, midpoint=({}, {})",
                 z, layerBounds, midX, midY);

        return new double[] { midX, midY };
    }

    private void straightenLayer(final Double z,
                                 final double zMin,
                                 final double zRange,
                                 final double totalOffsetX,
                                 final double totalOffsetY)
            throws Exception {

        LOG.info("straightenLayer: entry, z={}", z);

        final ResolvedTileSpecCollection tiles = sourceDataClient.getResolvedTiles(parameters.stack, z);

        if (tiles.getTileCount() == 0) {
            LOG.info("straightenLayer: no tiles for z={}, skipping", z);
            return;
        }

        // Compute the interpolation factor for this layer
        // At zMin, factor = 0 (no movement)
        // At zMax, factor = 1 (full offset applied, but negated to bring it back to first layer position)
        final double factor = (z - zMin) / zRange;

        // The translation needed to straighten this layer
        // We negate the offset because we want to move layers back toward the first layer's position
        final double translateX = -factor * totalOffsetX;
        final double translateY = -factor * totalOffsetY;

        if (Math.abs(translateX) > 0.001 || Math.abs(translateY) > 0.001) {
            final AffineModel2D translationModel = new AffineModel2D();
            translationModel.set(1, 0, 0, 1, translateX, translateY);

            final LeafTransformSpec translationTransform = new LeafTransformSpec(
                    translationModel.getClass().getName(),
                    translationModel.toDataString());

            tiles.preConcatenateTransformToAllTiles(translationTransform);

            LOG.info("straightenLayer: applied translation ({}, {}) to {} tiles for z={}",
                     translateX, translateY, tiles.getTileCount(), z);
        } else {
            LOG.info("straightenLayer: no significant translation needed for z={}", z);
        }

        targetDataClient.saveResolvedTiles(tiles, parameters.targetStack, z);

        LOG.info("straightenLayer: exit, saved {} tiles for z={}", tiles.getTileCount(), z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(StackStraighteningClient.class);
}
