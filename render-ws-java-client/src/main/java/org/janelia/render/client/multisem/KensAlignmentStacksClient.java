package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
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

    public KensAlignmentStacksClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    void fixStackData() throws Exception {
        final StackMetaData fromStackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        final int slab = extractSlabNumber(parameters.stack);
        final RecapKensAlignmentTools.SlabInfo slabInfo = RecapKensAlignment.slabInfoForSlab(slab);
        final Map<Integer, RecapKensAlignment.TransformedZLayer> transformedZLayers = RecapKensAlignment.reconstruct(slabInfo);

        renderDataClient.setupDerivedStack(fromStackMetaData, parameters.targetStack);

        for (final Double z : renderDataClient.getStackZValues(parameters.stack)) {
            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            final RecapKensAlignment.TransformedZLayer transformedZLayer = transformedZLayers.get(z.intValue());
            final Set<String> tileIdsToRemove = new HashSet<>();

            if (transformedZLayer == null) {
                // no transform for this z layer (some were skipped in the original alignment)
                continue;
            }

            final Map<Integer, RecapKensAlignment.TransformedImage> sfovToTransformedImage = new HashMap<>();
            transformedZLayer.transformedImages.forEach(tI -> {
                final String fileId = Path.of(tI.fileName).getFileName().toString();
                sfovToTransformedImage.put(extractSfovId(fileId), tI);
            });

            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                final int mfov = extractMfovId(tileSpec.getTileId());
                final int sfov = extractSfovId(tileSpec.getTileId());

                if (mfov == 10) {
                    // only the central MFOV (number 10) is aligned
                    fixTileSpec(tileSpec, sfovToTransformedImage.get(sfov));
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

        // scan correction
        final TransformSpec firstTransformSpec = tileSpec.getTransforms().getSpec(0);
        final TransformSpec scanCorrectionSpec = verifyCorrectScanCorrection(firstTransformSpec);
        transforms.addSpec(scanCorrectionSpec);

        // concatenate all alignment transforms
        final AffineModel2D concatenatedTransform = RecapKensAlignmentTools.concatenateModels(transformedImage);

        // translate 3px in -x direction to simulate the cropping after scan correction (applied before all other transformations)
        final int pxShift = 3;
        final TranslationModel2D cropTransform = new TranslationModel2D();
        cropTransform.set(-pxShift, 0);

        // adjust stack bounding box resulting from the crop (applied after all other transformations)
        final double[] origin = new double[] {0, 0};
        final double[] corner = new double[] {-pxShift, 0};
        concatenatedTransform.applyInPlace(origin);
        concatenatedTransform.applyInPlace(corner);
        final TranslationModel2D correctCropTransform = new TranslationModel2D();
        correctCropTransform.set(origin[0] - corner[0], origin[1] - corner[1]);

        concatenatedTransform.concatenate(cropTransform);
        concatenatedTransform.preConcatenate(correctCropTransform);

        final TransformSpec concatenatedTransformSpec = TransformSpec.create(concatenatedTransform);
        transforms.addSpec(concatenatedTransformSpec);

        tileSpec.setTransforms(transforms);
        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
    }

    // Make sure the correct scan correction transform is the first transform in the list
    private static TransformSpec verifyCorrectScanCorrection(final TransformSpec firstTransformSpec) {
        if (! (firstTransformSpec instanceof LeafTransformSpec)) {
            throw new IllegalArgumentException("first transform spec is not a leaf transform spec");
        }

        final LeafTransformSpec oldScanCorrection = (LeafTransformSpec) firstTransformSpec;
        if (!oldScanCorrection.getClassName().equals("org.janelia.alignment.transform.ExponentialFunctionOffsetTransform")) {
            throw new IllegalArgumentException("first transform spec is not a scan correction transform");
        }

		return firstTransformSpec;
    }

    private int extractSlabNumber(final String stack) {
        // stack name is of the form s001_m239_*
        return Integer.parseInt(stack.substring(1, 4));
    }

    private int extractMfovId(final String tileId) {
        // tileId is of the form xxx_mmmmmm_sss_*
        return Integer.parseInt(tileId.substring(4, 10));
    }

    private int extractSfovId(final String tileId) {
        // tileId is of the form xxx_mmmmmm_sss_*
        return Integer.parseInt(tileId.substring(11, 14));
    }

	private static final Logger LOG = LoggerFactory.getLogger(KensAlignmentStacksClient.class);
}
