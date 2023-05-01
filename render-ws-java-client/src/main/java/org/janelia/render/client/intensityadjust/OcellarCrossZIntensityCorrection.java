package org.janelia.render.client.intensityadjust;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.LinearIntensityMap8BitFilter;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.PreloadedImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.realtransform.AffineTransform;

public class OcellarCrossZIntensityCorrection {

    @SuppressWarnings("ConstantValue")
    public static void main(final String[] args) {

        final boolean visualizeData = true;

        try {
            final RenderDataClient dataClient =
                    new RenderDataClient("http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                                         "reiser",
                                         "Z0422_05_Ocellar");
            final String alignedIntensityCorrectedStack = "v9_acquire_align_ic";
            final double lastFourTileZ = 5827;
            final double firstSingleTileZ = lastFourTileZ + 1;

            if (visualizeData) {
                new ImageJ();
            }

            final FilterSpec filterSpec = deriveCrossLayerFilterSpec(dataClient,
                                                                     alignedIntensityCorrectedStack,
                                                                     lastFourTileZ,
                                                                     firstSingleTileZ,
                                                                     200,
                                                                     visualizeData);

            // uncomment to actually apply the derived filter spec to all single tiles ...
//            addAndSaveRelativeFilter(dataClient,
//                                     alignedIntensityCorrectedStack,
//                                     firstSingleTileZ,
//                                     filterSpec);

        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static FilterSpec deriveCrossLayerFilterSpec(final RenderDataClient dataClient,
                                                        final String alignedIntensityCorrectedStack,
                                                        final double pz,
                                                        final double qz,
                                                        final int overlapClipMarginPixels,
                                                        final boolean visualizeData)
            throws ExecutionException, InterruptedException, IOException {

        LOG.info("deriveCrossLayerFilterSpec: entry, alignedIntensityCorrectedStack={}, pz={}, qz={}, overlapClipMarginPixels={}",
                 alignedIntensityCorrectedStack,
                 pz,
                 qz,
                 overlapClipMarginPixels);

        FilterSpec crossLayerFilterSpec = null;

        final Rectangle pBounds = dataClient.getLayerBounds(alignedIntensityCorrectedStack, pz).toRectangle();
        final Rectangle qBounds = dataClient.getLayerBounds(alignedIntensityCorrectedStack, qz).toRectangle();

        Rectangle overlap = pBounds.intersection(qBounds);

        if (! overlap.isEmpty()) {
            final int margin2x = 2 * overlapClipMarginPixels;
            final boolean clipWidth = overlap.width > margin2x;
            final boolean clipHeight = overlap.height > margin2x;
            overlap = new Rectangle(clipWidth ? overlap.x + overlapClipMarginPixels : overlap.x,
                                    clipHeight ? overlap.y + overlapClipMarginPixels : overlap.y,
                                    clipWidth ? overlap.width - overlapClipMarginPixels : overlap.width,
                                    clipHeight ? overlap.height - overlapClipMarginPixels : overlap.height);

            // make cache large enough to hold tiles and masks for two layers
            final PreloadedImageProcessorCache imageProcessorCache =
                    new PreloadedImageProcessorCache(30_000L * 30_000L,
                                                     false,
                                                     false);

            final int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;

            final String stackUrl = dataClient.getUrls().getStackUrlString(alignedIntensityCorrectedStack);

            final TileSpec firstOverlapSpec = buildLocalOverlapTileSpec(overlap, pz, stackUrl, imageProcessorCache);
            final TileSpec secondOverlapSpec = buildLocalOverlapTileSpec(overlap, qz, stackUrl, imageProcessorCache);

            if (visualizeData) {
                showTileSpec("P original", firstOverlapSpec, 0.1, imageProcessorCache);
                showTileSpec("Q original", secondOverlapSpec, 0.1, imageProcessorCache);
            }

            final List<MinimalTileSpecWrapper> alignedOverlapBoxes = new ArrayList<>();
            alignedOverlapBoxes.add(new MinimalTileSpecWrapper(firstOverlapSpec));
            alignedOverlapBoxes.add(new MinimalTileSpecWrapper(secondOverlapSpec));

            final List<OnTheFlyIntensity> corrected =
                    AdjustBlock.correctIntensitiesForSliceTiles(alignedOverlapBoxes,
                                                                imageProcessorCache,
                                                                numCoefficients);

            final double[][] pCoefficients = corrected.get(0).getCoefficients();
            final double[][] qCoefficients = corrected.get(1).getCoefficients();
            final double[][] qRelativeToPCoefficients = normalizeCoefficientsForQRelativeToP(pCoefficients,
                                                                                             qCoefficients);

            final TileSpec correctedPSpec = deriveTileSpecWithFilter(firstOverlapSpec,
                                                                     numCoefficients,
                                                                     pCoefficients);
            final TileSpec correctedQSpec = deriveTileSpecWithFilter(secondOverlapSpec,
                                                                     numCoefficients,
                                                                     qCoefficients);
            final TileSpec qRelativeSpec = deriveTileSpecWithFilter(secondOverlapSpec,
                                                                    numCoefficients,
                                                                    qRelativeToPCoefficients);
            crossLayerFilterSpec = qRelativeSpec.getFilterSpec();

            if (visualizeData) {
                showTileSpec("P corrected", correctedPSpec, 0.1, imageProcessorCache);
                showTileSpec("Q corrected", correctedQSpec, 0.1, imageProcessorCache);
                showTileSpec("Q relative", qRelativeSpec, 0.1, imageProcessorCache);
            }

        } else {
            LOG.warn("deriveCrossLayerIntensityFilterData: stack {} z {} and {} do not overlap - is the stack aligned?",
                     alignedIntensityCorrectedStack, pz, qz);
        }

        return crossLayerFilterSpec;
    }
    public static TileSpec deriveTileSpecWithFilter(final TileSpec tileSpec,
                                                    final int numCoefficients,
                                                    final double[][] coefficients) {
        final TileSpec derivedTileSpec = tileSpec.slowClone();
        final LinearIntensityMap8BitFilter filter =
                new LinearIntensityMap8BitFilter(numCoefficients,
                                                 numCoefficients,
                                                 2,
                                                 coefficients);
        final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(),
                                                     filter.toParametersMap());
        derivedTileSpec.setFilterSpec(filterSpec);
        derivedTileSpec.convertSingleChannelSpecToLegacyForm();
        return derivedTileSpec;
    }

    public static double[][] normalizeCoefficientsForQRelativeToP(final double[][] pCoefficients,
                                                                  final double[][] qCoefficients) {
        final double[][] qRelativeToPCoefficients = new double[qCoefficients.length][2];
        final AffineTransform pTransform = new AffineTransform(1);
        final AffineTransform qTransform = new AffineTransform(1);
        for (int region = 0; region < qCoefficients.length; region++) {
            pTransform.set(pCoefficients[region]);
            qTransform.set(qCoefficients[region]);
            qTransform.preConcatenate(pTransform.inverse());
            qRelativeToPCoefficients[region] = qTransform.getRowPackedCopy();
        }
        return qRelativeToPCoefficients;
    }

    public static TileSpec buildRemoteOverlapTileSpec(final Rectangle overlap,
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

        tileSpec.convertSingleChannelSpecToLegacyForm();
        return tileSpec;
    }

    public static TileSpec buildLocalOverlapTileSpec(final Rectangle overlap,
                                                final double z,
                                                final String stackUrl,
                                                final PreloadedImageProcessorCache imageProcessorCache) {
        // Example core URL:
        //   http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/reiser/project/Z0422_05_Ocellar/stack/v7_acquire_align_ic/z/5827/box/-5000,-4500,8000,6000,0.1/png-image
        final String overlapUrl = stackUrl + "/z/" + z + "/box/" +
                                  overlap.x + "," + overlap.y + "," + overlap.width + "," + overlap.height +
                                  ",1.0/render-parameters";
        final RenderParameters renderParameters = RenderParameters.loadFromUrl(overlapUrl);
        final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                Renderer.renderImageProcessorWithMasks(renderParameters, imageProcessorCache);

        final TileSpec tileSpec = buildRemoteOverlapTileSpec(overlap, z, stackUrl);
        final String imageUrl = tileSpec.getFirstMipmapEntry().getValue().getImageUrl();
        final ByteProcessor overlapProcessor = ipwm.ip.convertToByteProcessor();
        imageProcessorCache.put(imageUrl, overlapProcessor);

        return tileSpec;
    }

    public static void showTileSpec(final String title,
                                     final TileSpec tileSpec,
                                     final double renderScale,
                                     final ImageProcessorCache imageProcessorCache) {
        LOG.info("showTileSpec: {} spec is:\n{}", title, tileSpec.toJson());

        final RenderParameters secondSpecRenderParameters = RenderParameters.fromTileSpec(tileSpec);
        secondSpecRenderParameters.setScale(renderScale);

        final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                Renderer.renderImageProcessorWithMasks(secondSpecRenderParameters, imageProcessorCache);
        new ImagePlus(title, ipwm.ip.convertToByteProcessor()).show();
    }

    public static void addAndSaveRelativeFilter(final RenderDataClient dataClient,
                                                final String alignedIntensityCorrectedStack,
                                                final double firstSingleTileZ,
                                                final FilterSpec filterSpec)
            throws IOException {

        final StackMetaData stackMetaData = dataClient.getStackMetaData(alignedIntensityCorrectedStack);
        final double stackMaxZ = stackMetaData.getStats().getStackBounds().getMaxZ();

        dataClient.ensureStackIsInLoadingState(alignedIntensityCorrectedStack, stackMetaData);

        final int tilesPerBatch = 1000;
        double z = firstSingleTileZ;
        for (; z < stackMaxZ; z += tilesPerBatch) {
            final double maxZ = z + tilesPerBatch - 1;
            final ResolvedTileSpecCollection resolvedTilesForBatch =
                    dataClient.getResolvedTilesForZRange(alignedIntensityCorrectedStack, z, maxZ);
            for (final TileSpec tileSpec : resolvedTilesForBatch.getTileSpecs()) {
                tileSpec.setFilterSpec(filterSpec);
            }

            LOG.info("addAndSaveRelativeFilter: added filter to {} tile specs in z {} to {}",
                     resolvedTilesForBatch.getTileCount(), z, maxZ);

            dataClient.saveResolvedTiles(resolvedTilesForBatch, alignedIntensityCorrectedStack, null);
        }

        dataClient.setStackState(alignedIntensityCorrectedStack, StackMetaData.StackState.COMPLETE);
    }

    private static final Logger LOG = LoggerFactory.getLogger(OcellarCrossZIntensityCorrection.class);
}
