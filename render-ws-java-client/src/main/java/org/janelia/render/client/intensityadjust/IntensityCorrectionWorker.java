package org.janelia.render.client.intensityadjust;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.LinearIntensityMap8BitFilter;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
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

    public static List<TileSpec> deriveCrossLayerIntensityFilterData(final String baseRenderUrl,
                                                                     final String renderOwner,
                                                                     final String renderProject,
                                                                     final String alignedRenderStack,
                                                                     final int firstIntegralZ,
                                                                     final int overlapClipMarginPixels)
            throws ExecutionException, InterruptedException, IOException {

        LOG.info("deriveCrossLayerIntensityFilterData: entry, firstIntegralZ={}", firstIntegralZ);

        final RenderDataClient dataClient = new RenderDataClient(baseRenderUrl, renderOwner, renderProject);

        @SuppressWarnings("UnnecessaryLocalVariable")
        final double firstZ = firstIntegralZ;
        final Rectangle firstBounds = dataClient.getLayerBounds(alignedRenderStack, firstZ).toRectangle();

        final double secondZ = firstZ + 1;
        final Rectangle secondBounds = dataClient.getLayerBounds(alignedRenderStack, secondZ).toRectangle();

        Rectangle overlap = firstBounds.intersection(secondBounds);

        final List<TileSpec> correctedTileSpecs = new ArrayList<>();

        if (! overlap.isEmpty()) {
            final int margin2x = 2 * overlapClipMarginPixels;
            final boolean clipWidth = overlap.width > margin2x;
            final boolean clipHeight = overlap.height > margin2x;
            overlap = new Rectangle(clipWidth ? overlap.x + overlapClipMarginPixels : overlap.x,
                                    clipHeight ? overlap.y + overlapClipMarginPixels : overlap.y,
                                    clipWidth ? overlap.width - overlapClipMarginPixels : overlap.width,
                                    clipHeight ? overlap.height - overlapClipMarginPixels : overlap.height);

            // make cache large enough to hold shared mask processors
            final ImageProcessorCache imageProcessorCache =
                    new ImageProcessorCache(15_000L * 15_000L,
                                            false,
                                            false);

            final int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;

            final String stackUrl = dataClient.getUrls().getStackUrlString(alignedRenderStack);

            final TileSpec firstOverlapSpec = buildOverlapTileSpec(overlap, firstZ, stackUrl);
            final TileSpec secondOverlapSpec = buildOverlapTileSpec(overlap, secondZ, stackUrl);

            final List<MinimalTileSpecWrapper> alignedOverlapBoxes = new ArrayList<>();
            alignedOverlapBoxes.add(new MinimalTileSpecWrapper(firstOverlapSpec));
            alignedOverlapBoxes.add(new MinimalTileSpecWrapper(secondOverlapSpec));

            final ArrayList<OnTheFlyIntensity> corrected =
                    AdjustBlock.correctIntensitiesForSliceTiles(alignedOverlapBoxes,
                                                                imageProcessorCache,
                                                                numCoefficients);

            for (final OnTheFlyIntensity onTheFlyIntensity : corrected) {
                final TileSpec tileSpec = onTheFlyIntensity.getMinimalTileSpecWrapper().getTileSpec();
                final LinearIntensityMap8BitFilter filter =
                        new LinearIntensityMap8BitFilter(numCoefficients,
                                                         numCoefficients,
                                                         2,
                                                         onTheFlyIntensity.getCoefficients());
                final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(),
                                                             filter.toParametersMap());
                tileSpec.setFilterSpec(filterSpec);
                tileSpec.convertSingleChannelSpecToLegacyForm();
                correctedTileSpecs.add(tileSpec);
            }
        } else {
            LOG.warn("deriveCrossLayerIntensityFilterData: stack {} z {} and {} do not overlap - is the stack aligned?",
                     alignedRenderStack, firstZ, secondZ);
        }

        return correctedTileSpecs;
    }

    public static TileSpec buildOverlapTileSpec(final Rectangle overlap,
                                                final double z,
                                                final String stackUrl) {
        final TileSpec tileSpec = new TileSpec();
        final String zString = String.valueOf(z);
        tileSpec.setTileId("z_" + zString + "_overlap");
        tileSpec.setLayout(new LayoutData(zString,
                                          null,
                                          null,
                                          0,
                                          0,
                                          0.0,
                                          0.0,
                                          0.0));
        tileSpec.setZ(z);
        tileSpec.setBoundingBox(new Rectangle(0, 0, (int) overlap.getWidth(), (int) overlap.getHeight()),
                                tileSpec.getMeshCellSize());
        tileSpec.setWidth(overlap.getWidth());
        tileSpec.setHeight(overlap.getHeight());

        // Notes:
        // - Use png instead of tif for URL because ImageJ Opener loads URL stream twice for tif URLs!
        //   Doesn't make much difference for normal small tiles, but really slows things down for
        //   intensity corrected stacks with large tile areas.
        // - URLs need to have fake format=.png suffixes (which render-ws ignores) so that ImageJ Opener can handle them

        // Example core URL:
        //   http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/reiser/project/Z0422_05_Ocellar/stack/v7_acquire_align_ic/z/5827/box/-5000,-4500,8000,6000,0.1/png-image
        final String overlapUrl = stackUrl + "/z/" + z + "/box/" +
                                  overlap.x + "," + overlap.y + "," + overlap.width + "," + overlap.height +
                                  ",1.0/png-image?format=.png";

        final ImageAndMask imageAndMask = new ImageAndMask(overlapUrl, null);
        final ChannelSpec channelSpec = new ChannelSpec();
        channelSpec.putMipmap(0, imageAndMask);
        tileSpec.addChannel(channelSpec);

        return tileSpec;
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityCorrectionWorker.class);
}
