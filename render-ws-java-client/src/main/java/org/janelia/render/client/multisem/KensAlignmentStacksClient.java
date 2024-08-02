package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.transform.ExponentialFunctionOffsetTransformWithWrongSign;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Java client to create a stack that has the same transforms as Ken's original prototype alignment of the central
 * MFOV of wafer 53.
 */
public class KensAlignmentStacksClient {

    @SuppressWarnings("ALL")
    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Name of stack from which tile specs should be read",
                required = true)
        private String stack;

        @Parameter(
                names = "--targetStack",
                description = "Name of stack to which updated tile specs should be written",
                required = true)
        private String targetStack;
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final KensAlignmentStacksClient client = new KensAlignmentStacksClient(parameters);
                client.fixStackData();
            }
        };
        clientRunner.run();
    }


    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    private KensAlignmentStacksClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    private void fixStackData() throws Exception {
        final StackMetaData fromStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        final int stageId = extractStageId(parameters.stack);
        final Map<Integer, RecapKensAlignment.TransformedZLayer> transformedZLayers = RecapKensAlignment.reconstruct(stageId + 1);

        renderDataClient.setupDerivedStack(fromStackMetaData, parameters.targetStack);

        for (final Double z : renderDataClient.getStackZValues(parameters.stack)) {
            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            final RecapKensAlignment.TransformedZLayer transformedZLayer = transformedZLayers.get(z.intValue());
            final Set<String> tileIdsToRemove = new HashSet<>();

            if (transformedZLayer == null) {
                // no transform for this z layer (some were skipped in the original alignment)
                continue;
            }

            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                final String[] mfovAndSfov = Utilities.getSFOVForTileId(tileSpec.getTileId()).split("_");
                final int mfov = Integer.parseInt(mfovAndSfov[1]);
                final int sfov = Integer.parseInt(mfovAndSfov[2]);

                if (mfov == 10) {
                    // only the central MFOV (number 10) is aligned
                    fixTileSpec(tileSpec, transformedZLayer.transformedImages.get(sfov - 1));
                } else {
                    tileIdsToRemove.add(tileSpec.getTileId());
                }
            }
            resolvedTiles.removeTileSpecs(tileIdsToRemove);
            renderDataClient.saveResolvedTiles(resolvedTiles, parameters.targetStack, z);
        }

        renderDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
    }

    private void fixTileSpec(final TileSpec tileSpec, final RecapKensAlignment.TransformedImage transformedImage) {

        final ListTransformSpec transforms = new ListTransformSpec();

        final TransformSpec firstTransformSpec = tileSpec.getTransforms().getSpec(0);
        final TransformSpec scanCorrectionSpec = convertToCorrectScanCorrection(firstTransformSpec);
        transforms.addSpec(scanCorrectionSpec);

        for (final AbstractAffineModel2D<?> model : transformedImage.models) {
            final TransformSpec transformSpec = TransformSpec.create(model);
            transforms.addSpec(transformSpec);
        }

        tileSpec.setTransforms(transforms);
    }

    // In the original alignment, the scan correction transform was applied with the wrong sign
    // This method converts our correct scan correction transform to the one used in the original alignment
    private static TransformSpec convertToCorrectScanCorrection(final TransformSpec firstTransformSpec) {
        if (! (firstTransformSpec instanceof LeafTransformSpec)) {
            throw new IllegalArgumentException("first transform spec is not a leaf transform spec");
        }

        final LeafTransformSpec oldScanCorrection = (LeafTransformSpec) firstTransformSpec;
        if (!oldScanCorrection.getClassName().equals("org.janelia.alignment.transform.ExponentialFunctionOffsetTransform")) {
            throw new IllegalArgumentException("first transform spec is not a scan correction transform");
        }

        final String[] coefficients = oldScanCorrection.getDataString().split(",");
        final CoordinateTransform scanCorrection = new ExponentialFunctionOffsetTransformWithWrongSign(
                Double.parseDouble(coefficients[0]),
                Double.parseDouble(coefficients[1]),
                Double.parseDouble(coefficients[2]),
                Integer.parseInt(coefficients[3]));

		return TransformSpec.create(scanCorrection);
    }

    private int extractStageId(final String stack) {
        final String[] parts = stack.split("_");
        return Integer.parseInt(parts[0].substring(1));
    }

	private static final Logger LOG = LoggerFactory.getLogger(KensAlignmentStacksClient.class);
}
