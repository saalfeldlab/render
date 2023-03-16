package org.janelia.render.client.intensityadjust;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

import mpicbg.models.AffineModel1D;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import net.imglib2.realtransform.AffineTransform;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.LinearIntensityMap8BitFilter;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core logic for distributed intensity correction processing that can be used for either LSF Array or Spark jobs.
 *
 * @author Eric Trautman
 */
public class IntensityCorrectionWorker implements Serializable {

	public static void updateCoefficients()
	{
		/*
		coeff_a = load coeffiecients for upper plane
		coeff_b = load coeffiecients for lower plane

		transforms_a = convertToAffine1D( coeff_a ) // new AffineTransform( 1 ).set( mul, add );
		transforms_b = convertToAffine1D( coeff_b ) // new AffineTransform( 1 ).set( mul, add );

		for each transform do:
			transform_b = transform_b.preConcatenate( transform_a.inverse() );

		coeff_b = createCoeffiecients( transform_b ) // Those need to be preconcatenated to the existing ones, but they are all identity transforms, so we can simply replace them directly
		save( coeff_b)
		discard( coeff_a)
		*/
	}

    private final IntensityAdjustParameters parameters;
    private final List<Double> zValues;
    private final StackMetaData stackMetaData;
    private final String slicePathFormatSpec;

    public IntensityCorrectionWorker(final IntensityAdjustParameters parameters,
                                     final RenderDataClient dataClient) throws IOException {
        this.parameters = parameters;

        this.zValues = dataClient.getStackZValues(parameters.stack,
                                                  parameters.layerRange.minZ,
                                                  parameters.layerRange.maxZ,
                                                  parameters.zValues);
        if (this.zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        this.stackMetaData = dataClient.getStackMetaData(parameters.stack);
        if (parameters.intensityCorrectedFilterStack == null) {
            final File sectionRootDirectory = parameters.getSectionRootDirectory(new Date());
            FileUtil.ensureWritableDirectory(sectionRootDirectory);
            this.slicePathFormatSpec = parameters.getSlicePathFormatSpec(stackMetaData,
                                                                         sectionRootDirectory);
        } else {
            this.slicePathFormatSpec = null;
        }

        if (parameters.deriveFilterData()) {
            dataClient.setupDerivedStack(stackMetaData, parameters.intensityCorrectedFilterStack);
        }
    }

    public List<Double> getzValues() {
        return zValues;
    }

    public void correctZ(final RenderDataClient dataClient,
                         final Double z)
            throws ExecutionException, InterruptedException, IOException {

        final ResolvedTileSpecCollection resolvedTiles = dataClient.getResolvedTiles(parameters.stack, z);
        resolvedTiles.resolveTileSpecs();

        if (parameters.deriveFilterData()) {
            deriveAndStoreIntensityFilterData(dataClient,
                                              resolvedTiles,
                                              z.intValue());
        } else {
            renderIntensityAdjustedScape(dataClient,
                                         resolvedTiles,
                                         z.intValue());
        }
    }

    public void deriveAndStoreIntensityFilterData(final RenderDataClient dataClient,
                                                  final ResolvedTileSpecCollection resolvedTiles,
                                                  final int integralZ)
            throws ExecutionException, InterruptedException, IOException {

        LOG.info("deriveAndStoreIntensityFilterData: entry, integralZ={}", integralZ);

        if (resolvedTiles.getTileCount() > 1) {
            // make cache large enough to hold shared mask processors
            final ImageProcessorCache imageProcessorCache =
                    new ImageProcessorCache(15_000L * 15_000L,
                                            false,
                                            false);

            final int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;

            final List<MinimalTileSpecWrapper> tilesForZ = AdjustBlock.getTilesForZ(resolvedTiles);
            final ArrayList<OnTheFlyIntensity> corrected =
                    AdjustBlock.correctIntensitiesForSliceTiles(tilesForZ,
                                                                imageProcessorCache,
                                                                numCoefficients);

            for (final OnTheFlyIntensity onTheFlyIntensity : corrected) {
                final String tileId = onTheFlyIntensity.getMinimalTileSpecWrapper().getTileId();
                final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);
                final LinearIntensityMap8BitFilter filter =
                        new LinearIntensityMap8BitFilter(numCoefficients,
                                                         numCoefficients,
                                                         2,
                                                         onTheFlyIntensity.getCoefficients());
                final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(),
                                                             filter.toParametersMap());
                tileSpec.setFilterSpec(filterSpec);
                tileSpec.convertSingleChannelSpecToLegacyForm();
            }
        } else {
            final String tileCountMsg = resolvedTiles.getTileCount() == 1 ? "1 tile" : "0 tiles";
            LOG.info("deriveAndStoreIntensityFilterData: skipping correction because z {} contains {}",
                     integralZ, tileCountMsg);
        }

        dataClient.saveResolvedTiles(resolvedTiles,
                                     parameters.intensityCorrectedFilterStack,
                                     (double) integralZ);
    }

    public void renderIntensityAdjustedScape(final RenderDataClient dataClient,
                                             final ResolvedTileSpecCollection resolvedTiles,
                                             final int integralZ)
            throws ExecutionException, InterruptedException, IOException {

        LOG.info("renderIntensityAdjustedScape: entry, integralZ={}", integralZ);

        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();

        final String parametersUrl =
                dataClient.getRenderParametersUrlString(parameters.stack,
                                                        stackBounds.getMinX(),
                                                        stackBounds.getMinY(),
                                                        integralZ,
                                                        (int) (stackBounds.getDeltaX() + 0.5),
                                                        (int) (stackBounds.getDeltaY() + 0.5),
                                                        1.0,
                                                        null);

        final RenderParameters sliceRenderParameters = RenderParameters.loadFromUrl(parametersUrl);

        // make cache large enough to hold shared mask processors
        final ImageProcessorCache imageProcessorCache =
                new ImageProcessorCache(15_000L * 15_000L,
                                        false,
                                        false);

        final TransformMeshMappingWithMasks.ImageProcessorWithMasks slice;
//        switch (correctionMethod) {
//            case GAUSS:
//            case GAUSS_WEIGHTED:
//                slice = AdjustBlock.renderIntensityAdjustedSliceGauss(stack,
//                                                                      dataClient,
//                                                                      interval,
//                                                                      CorrectionMethod.GAUSS_WEIGHTED.equals(correctionMethod),
//                                                                      false,
//                                                                      integralZ);
//
//                break;
//            case GLOBAL_PER_SLICE:
        slice = AdjustBlock.renderIntensityAdjustedSliceGlobalPerSlice(resolvedTiles,
                                                                       sliceRenderParameters,
                                                                       imageProcessorCache,
                                                                       integralZ,
                                                                       AdjustBlock.DEFAULT_NUM_COEFFICIENTS);
//                break;
//            default:
//                throw new UnsupportedOperationException("only support GLOBAL_PER_SLICE for hack");
//                slice = AdjustBlock.renderIntensityAdjustedSlice(stack,
//                                                                 dataClient,
//                                                                 interval,
//                                                                 1.0,
//                                                                 false,
//                                                                 integralZ);
//                break;
//        }

        final BufferedImage sliceImage = slice.ip.getBufferedImage();

        final String slicePath = String.format(slicePathFormatSpec, integralZ);

        Utils.saveImage(sliceImage, slicePath, parameters.format, false, 0.85f);
    }

    public void completeCorrectedStackAsNeeded(final RenderDataClient dataClient)
            throws IOException {
        if (parameters.deriveFilterData() && parameters.completeCorrectedStack) {
            dataClient.setStackState(parameters.intensityCorrectedFilterStack,
                                     StackMetaData.StackState.COMPLETE);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityCorrectionWorker.class);
}