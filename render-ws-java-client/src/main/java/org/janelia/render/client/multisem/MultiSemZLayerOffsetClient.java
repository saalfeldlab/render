package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImagePlus;
import ij.gui.Roi;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client that compares adjacent stack layers and determines how much each layer should be offset in x and y
 * before performing detailed match derivation.
 * If an offsetStackSuffix is specified, the client will create new stacks that incorporate the derived offsets.
 */
public class MultiSemZLayerOffsetClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @Parameter(
                names = "--offsetStackSuffix",
                description = "If specified, create a new stacks with this suffix (e.g. '_offset') that contain " +
                              "tile specs positioned with the offsets derived by the client.")
        public String offsetStackSuffix;

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MultiSemZLayerOffsetClient client = new MultiSemZLayerOffsetClient(parameters);
                client.deriveAllZOffsets();
            }
        };
        clientRunner.run();
    }

    private final MultiProjectParameters multiProject;
    private final String offsetStackSuffix;

    public MultiSemZLayerOffsetClient(final Parameters parameters) {
        this.multiProject = parameters.multiProject;
        this.offsetStackSuffix = parameters.offsetStackSuffix;
    }

    public void deriveAllZOffsets()
            throws IOException {

        final RenderDataClient defaultDataClient = multiProject.getDataClient();

        final List<StackWithZValues> stackWithZValuesList = multiProject.buildListOfStackWithAllZ();
        for (final StackWithZValues stackWithZValues : stackWithZValuesList) {
            final StackId stackId = stackWithZValues.getStackId();
            final RenderDataClient dataClient = defaultDataClient.buildClient(stackId.getOwner(),
                                                                              stackId.getProject());

            // To produce early test result (z 76.0 needs offset of -4666, -388)
            // from the original shift area that Preibisch noticed,
            // hack the zValues below as:
            //   final List<Double> zValues = List.of(75.0, 76.0);
            //
            // and hack the following in deriveBoundsForRenderRegion:
            //   final String magcMfovWithLargestTileCount = "0160_m0018";
            //   ...
            //   regionBounds = new Bounds(60000.0, 125000.0, 70000.0, 135000.0);
            //
            // and use the following parameters:
            //   "--baseDataUrl", "http://10.40.3.113:8080/render-ws/v1",
            //   "--owner", "hess_wafers_60_61",
            //   "--project", "w60_serial_360_to_369",
            //   "--stack", "w60_s360_r00_d20_gc"
            //
            // This shows that the offsets need to be calculated per MFOV and not per z layer
            // because the offsets for z 75.0 and 76.0 with the default region are 0, -3.

            final List<Double> zValues = stackWithZValues.getzValues();
            final Map<Double, int[]> zToOffsets = deriveZOffsetsForStack(dataClient, stackId, zValues);
            if (offsetStackSuffix != null) {
                createOffsetStack(dataClient, stackId, zValues, offsetStackSuffix, zToOffsets);
            }
        }

    }

    public static Map<Double, int[]> deriveZOffsetsForStack(final RenderDataClient dataClient,
                                                            final StackId stackId,
                                                            final List<Double> zValues)
            throws IOException {

        final Map<Double, int[]> zToOffsets = new HashMap<>();

        final String baseDataUrlForStack = dataClient.getBaseDataUrl();
        final Double firstZ = zValues.get(0);
        final Bounds regionBounds = deriveBoundsForRenderRegion(dataClient, stackId, firstZ);

        final long fullScalePixelCount = (long) (Math.ceil(regionBounds.getDeltaX()) * Math.ceil(regionBounds.getDeltaY()));
        final int optimalPixelCount = 3000 * 3000;
        final double renderScale = Math.min(1.0, ((double) optimalPixelCount / fullScalePixelCount));

        final StitchingParameters stitchingParameters = createStitchingParameters(stackId.getStack());

        ImagePlus previousLayerImagePlus = renderRegion(baseDataUrlForStack,
                                                        stackId,
                                                        firstZ,
                                                        regionBounds,
                                                        renderScale);
        Double previousZ = firstZ;
        int previousFullScaleOffsetX = 0;
        int previousFullScaleOffsetY = 0;

        final Roi roi = new Roi(0, 0, previousLayerImagePlus.getWidth(), previousLayerImagePlus.getHeight());

        for (int zIndex = 1; zIndex < zValues.size(); zIndex++) {
            final Double z = zValues.get(zIndex);
            final ImagePlus layerImagePlus = renderRegion(baseDataUrlForStack,
                                                          stackId,
                                                          z,
                                                          regionBounds,
                                                          renderScale);
            final PairWiseStitchingResult result =
                    PairWiseStitchingImgLib.stitchPairwise(previousLayerImagePlus,
                                                           layerImagePlus,
                                                           roi, roi, 0, 0,
                                                           stitchingParameters);
            int fullScaleOffsetX = (int) (result.getOffset(0) / renderScale);
            int fullScaleOffsetY = (int) (result.getOffset(1) / renderScale);

            LOG.info("deriveZOffsetsForStack: {} z {} needs offset of {}, {} from z {}",
                     stackId.toDevString(), z, fullScaleOffsetX, fullScaleOffsetY, previousZ);

            fullScaleOffsetX += previousFullScaleOffsetX;
            fullScaleOffsetY += previousFullScaleOffsetY;

            LOG.info("deriveZOffsetsForStack: {} z {} needs offset of {}, {} from z {}",
                     stackId.toDevString(), z, fullScaleOffsetX, fullScaleOffsetY, firstZ);

            if (zToOffsets.containsKey(previousZ)) {
                final int[] previousOffsets = zToOffsets.get(previousZ);
                fullScaleOffsetX += previousOffsets[0];
                fullScaleOffsetY += previousOffsets[1];
            }
            zToOffsets.put(z, new int[] { fullScaleOffsetX, fullScaleOffsetY });

            previousLayerImagePlus = layerImagePlus;
            previousZ = z;
            previousFullScaleOffsetX = fullScaleOffsetX;
            previousFullScaleOffsetY = fullScaleOffsetY;
        }

        LOG.info("deriveZOffsetsForStack: returning offsets for {} layers in {}",
                 zToOffsets.size(), stackId.toDevString());

        return zToOffsets;
    }

    public static Bounds deriveBoundsForRenderRegion(final RenderDataClient dataClient,
                                                     final StackId stackId,
                                                     final Double firstZ)
            throws IOException, IllegalArgumentException {

        final List<TileBounds> tileBoundsList = dataClient.getTileBounds(stackId.getStack(), firstZ);

        if (tileBoundsList.isEmpty()) {
            throw new IllegalArgumentException("no tiles found for " + stackId);
        }

        final Map<String, Map<String, TileBounds>> magcMfovToSfovBounds = new HashMap<>();
        for (final TileBounds tileBounds : tileBoundsList) {
            final String mFOVId = MultiSemUtilities.getMagcMfovForTileId(tileBounds.getTileId());
            final Map<String, TileBounds> sfovIndexToBounds = magcMfovToSfovBounds.computeIfAbsent(mFOVId, k -> new HashMap<>());
            final String sfovIndex = MultiSemUtilities.getSFOVIndexForTileId(tileBounds.getTileId());
            sfovIndexToBounds.put(sfovIndex, tileBounds);
        }

        // final String magcMfovWithLargestTileCount = "0160_m0018"; // for testing
        final String magcMfovWithLargestTileCount =
                magcMfovToSfovBounds.keySet().stream()
                        .max(Comparator.comparingInt(key -> {
                            final Map<String, TileBounds> sfovIndexToBounds = magcMfovToSfovBounds.get(key);
                            return sfovIndexToBounds.size();
                        }))
                        .orElse(null); // should never get here because tileBoundsList is not empty

        final Map<String, TileBounds> sfovIndexToBounds = magcMfovToSfovBounds.get(magcMfovWithLargestTileCount);
        final List<String> sortedSfovIndices = sfovIndexToBounds.keySet().stream().sorted().collect(Collectors.toList());

        final String firstSfovIndex = sortedSfovIndices.get(0);
        final int stopValue = Math.min(7, sortedSfovIndices.size());

        // sFOVs within region when firstSfovIndex is 0 and stopValue is 7:
        //
        //     27 -- 12 -- 11 -- 10 -- 22
        //     -- 13 -- 04 -- 03 -- 09 --
        //     14 -- 05 -- 01 -- 02 -- 08
        //     -- 15 -- 06 -- 07 -- 19 --
        //     31 -- 16 -- 17 -- 18 -- 36

        Bounds regionBounds = sfovIndexToBounds.get(firstSfovIndex);
        String sfovIndex = firstSfovIndex;
        for (int i = 1; i < stopValue; i++) {
            sfovIndex = sortedSfovIndices.get(i);
            regionBounds = regionBounds.union(sfovIndexToBounds.get(sfovIndex));
        }

        // regionBounds = new Bounds(60000.0, 125000.0, 70000.0, 135000.0); // for testing

        LOG.info("deriveBoundsForRenderRegion: returning {} for {} mFOV {} with {} tiles, region was derived using {} sFOVs ({} to {})",
                 regionBounds, stackId.toDevString(), magcMfovWithLargestTileCount, sortedSfovIndices.size(),
                 stopValue, firstSfovIndex, sfovIndex);

        return regionBounds;
    }

    public static ImagePlus renderRegion(final String baseDataUrl,
                                         final StackId stackId,
                                         final Double z,
                                         final Bounds regionBounds,
                                         final double renderScale) {

        final String url = String.format("%s/owner/%s/project/%s/stack/%s/z/%s/box/%d,%d,%d,%d,%s/render-parameters",
                                         baseDataUrl, stackId.getOwner(), stackId.getProject(), stackId.getStack(), z,
                                         regionBounds.getX(), regionBounds.getY(), regionBounds.getWidth(), regionBounds.getHeight(),
                                         renderScale);
        final RenderParameters regionRenderParameters = RenderParameters.loadFromUrl(url);

        final TransformMeshMappingWithMasks.ImageProcessorWithMasks imageProcessorWithMasks =
                Renderer.renderImageProcessorWithMasks(regionRenderParameters,
                                                       ImageProcessorCache.DISABLED_CACHE,
                                                       null);

        return new ImagePlus("z " + z, imageProcessorWithMasks.ip);
    }

    public static StitchingParameters createStitchingParameters(final String fusedName) {
        final StitchingParameters stitchingParameters = new StitchingParameters();
        stitchingParameters.fusionMethod = 0;
        stitchingParameters.fusedName = fusedName;
        stitchingParameters.checkPeaks = 5;
        stitchingParameters.ignoreZeroValuesFusion = false;
        stitchingParameters.displayFusion = false;
        stitchingParameters.computeOverlap = true;
        stitchingParameters.subpixelAccuracy = false;
        stitchingParameters.xOffset = 0;
        stitchingParameters.yOffset = 0;
        stitchingParameters.zOffset = 0;
        stitchingParameters.channel1 = 0;
        stitchingParameters.channel2 = 0;
        stitchingParameters.timeSelect = 0;
        return stitchingParameters;
    }

    public static void createOffsetStack(final RenderDataClient dataClient,
                                         final StackId stackId,
                                         final List<Double> zValues,
                                         final String offsetStackSuffix,
                                         final Map<Double, int[]> zToOffsets)
            throws IOException {

        final StackMetaData stackMetaData = dataClient.getStackMetaData(stackId.getStack());
        final String offsetStack = stackId.getStack() + offsetStackSuffix;

        dataClient.setupDerivedStack(stackMetaData, offsetStack);

        for (final Double z : zValues) {
            final int[] offsets = zToOffsets.get(z);
            final ResolvedTileSpecCollection resolvedTiles = dataClient.getResolvedTiles(stackId.getStack(), z);
            if (offsets != null) {
                final String offsetDataString = offsets[0] + " " + offsets[1];
                final LeafTransformSpec transformSpec = new LeafTransformSpec("mpicbg.trakem2.transform.TranslationModel2D",
                                                                              offsetDataString);
                resolvedTiles.preConcatenateTransformToAllTiles(transformSpec);

                LOG.info("createOffsetStack: pre-concatenated offset {} for {} z {}",
                         offsetDataString, stackId.toDevString(), z);
            }
            dataClient.saveResolvedTiles(resolvedTiles, offsetStack, z);

        }

        dataClient.setStackState(offsetStack, StackMetaData.StackState.COMPLETE);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiSemZLayerOffsetClient.class);
}
