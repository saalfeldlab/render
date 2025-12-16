package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client to copy a stack's tiles, changing the image URL paths for all tiles in the resulting stack.
 * Currently implemented URL transformations:
 * - HayworthContrastPathTransformation: changes the image URL to point to images that have been contrast-adjusted using
 *   the Hayworth pipeline.
 * - BasicBackgroundCorrectionPathTransformation: changes the image URL to point to images that have been
 *   background-corrected using the BaSiC background correction method.
 * - GoogleTestCloudPathTransformation: changes the image URL to point to images stored
 *   in a test Google Cloud Storage bucket.
 *
 * @author Eric Trautman
 */
public class HackImageUrlPathClient {

    public enum PathTransformationType {
        HAYWORTH_CREEP_CORRECTION,
        HAYWORTH_CONTRAST,
        BASIC_BACKGROUND_CORRECTION,
        GOOGLE_CLOUD_TEST,
        GOOGLE_CLOUD_WAFER_60,
        NO_PATH_TRANSFORMATION;
        public UnaryOperator<String> getOperator() {
            switch (this) {
                case HAYWORTH_CREEP_CORRECTION:
                    return new HayworthCreepCorrectionPathTransformation();
                case HAYWORTH_CONTRAST:
                    return new HayworthContrastPathTransformation();
                case BASIC_BACKGROUND_CORRECTION:
                    return new BasicBackgroundCorrectionPathTransformation();
                case GOOGLE_CLOUD_TEST:
                    return new GoogleCloudTestPathTransformation();
                case GOOGLE_CLOUD_WAFER_60:
                    return new GoogleCloudWafer60PathTransformation();
                case NO_PATH_TRANSFORMATION:
                    return new NoPathTransformation();
                default:
                    throw new IllegalArgumentException("unsupported transformation type: " + this);
            }
        }
    }

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

        @Parameter(
                names = "--transformationType",
                description = "Type of transformation to apply to image URLs",
                required = true)
        private PathTransformationType transformationType;
    }

    private static final Logger LOG = LoggerFactory.getLogger(HackImageUrlPathClient.class);

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final UnaryOperator<String> pathTransformation = parameters.transformationType.getOperator();
                final HackImageUrlPathClient client = new HackImageUrlPathClient(parameters, pathTransformation);
                client.fixStackData();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final UnaryOperator<String> pathTransformation;

    public HackImageUrlPathClient(final Parameters parameters, final UnaryOperator<String> pathTransformation) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        this.pathTransformation = pathTransformation;
    }

    public void fixStackData() throws Exception {
        final StackMetaData fromStackMetaData = renderDataClient.getStackMetaData(parameters.stack);

        // remove mipmap path builder if it is defined since we did not generate mipmaps for the hacked source images
        fromStackMetaData.setCurrentMipmapPathBuilder(null);

        renderDataClient.setupDerivedStack(fromStackMetaData, parameters.targetStack);

        for (final Double z : renderDataClient.getStackZValues(parameters.stack)) {
            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.stack, z);
            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                fixTileSpec(tileSpec);
            }
            renderDataClient.saveResolvedTiles(resolvedTiles, parameters.targetStack, z);
        }

        renderDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
    }

    private void fixTileSpec(final TileSpec tileSpec) {
        final Integer zeroLevelKey = 0;

        for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {
            final Map.Entry<Integer, ImageAndMask> entry = channelSpec.getFirstMipmapEntry();
			if ((entry == null) || !zeroLevelKey.equals(entry.getKey())) {
                continue;
			}

			final ImageAndMask sourceImageAndMask = entry.getValue();

            final String imageUrl = sourceImageAndMask.getImageUrl();
            final String transformedUrl = pathTransformation.apply(imageUrl);

            if (transformedUrl == null) {
                throw new IllegalArgumentException("could not transform image URL: " + imageUrl);
            }

            final String derivedImageUrl;
            if (transformedUrl.startsWith("file:")) {
                final File hackFile = new File(transformedUrl);
                if (!hackFile.exists()) {
                    throw new IllegalArgumentException("target file does not exist: " + hackFile);
                }
                derivedImageUrl = "file:" + hackFile.getAbsolutePath();
            } else {
                derivedImageUrl = transformedUrl;
            }

            final ImageAndMask hackedImageAndMask;
            if (transformedUrl.startsWith("https://storage.googleapis.com/")) {
                hackedImageAndMask = sourceImageAndMask.copyWithDerivedUrls(derivedImageUrl,
                                                                            ImageLoader.LoaderType.IMAGEJ_DEFAULT_W_TIMEOUT,
                                                                            sourceImageAndMask.getImageSliceNumber(),
                                                                            sourceImageAndMask.getMaskUrl());
            } else {
                hackedImageAndMask = sourceImageAndMask.copyWithDerivedUrls(derivedImageUrl,
                                                                            sourceImageAndMask.getMaskUrl());
            }

            channelSpec.putMipmap(zeroLevelKey, hackedImageAndMask);
        }
    }



    private static class HayworthCreepCorrectionPathTransformation implements UnaryOperator<String> {
        private final Pattern PATH_PATTERN = Pattern.compile(
                "^file:/nearline/hess/ibeammsem/system_02/wafers/wafer_61/acquisition/scans/(scan_(\\d+))/slabs/slab_0145/mfovs/(mfov_(\\d+))/(sfov_\\d+\\.png)$");
        private static final Set<Integer> SCANS_TO_PATCH = Set.of(7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        private static final Set<Integer> MFOVS_TO_PATCH = Set.of(4, 5, 6, 9, 10, 13, 14, 15, 17, 18, 19, 21, 22, 23, 24, 25, 26);

        // original:    file:/nearline/hess/ibeammsem/system_02/wafers/wafer_61/acquisition/scans/scan_007/slabs/slab_0145/mfovs/mfov_0026/sfov_062.png
        // target:      file:/nrs/hess/render/distortion_correction_20251215/data/scan_007/slab_0145/mfov_0026/sfov_062.png
        // conditional: file:/nrs/hess/Hayworth/DISTORTION_CORRECTED/wafer_61/scan_007/slab_0145/mfov_0026/sfov_062.png
        @Override
        public String apply(final String path) {
            final Matcher matcher = PATH_PATTERN.matcher(path);
            if (matcher.matches()) {
                // Extract relevant numbers from the path
                final String scan = matcher.group(1);
                final int scanNumber = Integer.parseInt(matcher.group(2));
                final String mfov = matcher.group(3);
                final int mfovNumber = Integer.parseInt(matcher.group(4));
                final String sfov = matcher.group(5);

                if (SCANS_TO_PATCH.contains(scanNumber) && MFOVS_TO_PATCH.contains(mfovNumber)) {
                    return "/nrs/hess/Hayworth/DISTORTION_CORRECTED/wafer_61/" + scan + "/slab_0145/" + mfov + "/" + sfov;
                } else {
                    return "/nrs/hess/render/distortion_correction_20251215/data/" + scan + "/slab_0145/" + mfov + "/" + sfov;
                }
            } else {
                return null;
            }
        }
    }

    private static class HayworthContrastPathTransformation implements UnaryOperator<String> {
        private final Pattern PATH_PATTERN = Pattern.compile("^file:/nrs.*/(scan_\\d\\d\\d/)wafer.*/(\\d\\d\\d_/.*png)$");

        // original: file:/nrs/hess/data/hess_wafer_53/raw/imaging/msem/scan_001/wafer_53_scan_001_20220427_23-16-30/402_/000005/402_000005_001_2022-04-28T1457426331720.png
        // target:   file:/nrs/hess/data/hess_wafer_53/msem_with_hayworth_contrast/scan_001/402_/000005/402_000005_001_2022-04-28T1457426331720.png
        @Override
        public String apply(final String path) {
            final Matcher matcher = PATH_PATTERN.matcher(path);
            if (matcher.matches()) {
                return "/nrs/hess/data/hess_wafer_53/msem_with_hayworth_contrast/" + matcher.group(1) + matcher.group(2);
            } else {
                return null;
            }
        }
    }

    private static class BasicBackgroundCorrectionPathTransformation implements UnaryOperator<String> {

        // original: file:/nrs/hess/ibeammsem/system_02/wafers/wafer_60/acquisition/scans/scan_004/slabs/slab_0399/mfovs/mfov_0000/sfov_001.png
        // target:   file:/nrs/hess/ibeammsem/system_02/wafers/wafer_60/acquisition/background_corrected/scans/scan_004/slabs/slab_0399/mfovs/mfov_0000/sfov_001.png
        @Override
        public String apply(final String path) {
            return path.substring(5, 62) + "/background_corrected" + path.substring(62);
        }
    }

    private static class GoogleCloudTestPathTransformation
            implements UnaryOperator<String> {
        // original:                 file:/nrs/hess/ibeammsem/system_02/wafers/wafer_60/acquisition/scans/scan_004/slabs/slab_0399/mfovs/mfov_0023/sfov_073.png
        // target:   https://storage.googleapis.com/janelia-spark-test/FlyMSEM/wafer_60/acquisition/scans/scan_004/slabs/slab_0399/mfovs/mfov_0023/sfov_073.png
        @Override
        public String apply(final String path) {
            return "https://storage.googleapis.com/janelia-spark-test/FlyMSEM/" + path.substring(42);
        }
    }

    private static class GoogleCloudWafer60PathTransformation
            implements UnaryOperator<String> {
        // original: file:/nearline/hess/ibeammsem/system_02/wafers/wafer_60/acquisition/scans/scan_004/slabs/slab_0160/mfovs/mfov_0000/sfov_002.png
        // target:        https://storage.googleapis.com/janelia-spark-test/hess_wafer_60_data/scan_004/slab_0160/mfov_0000/sfov_002.png
        @Override
        public String apply(final String path) {
            final String hackedPath = path.substring(74)
                    .replace("/slabs/", "/")
                    .replace("/mfovs/", "/");
            return "https://storage.googleapis.com/janelia-spark-test/hess_wafer_60_data/" + hackedPath;
        }
    }

    private static class NoPathTransformation
            implements UnaryOperator<String> {
        @Override
        public String apply(final String path) {
            return path;
        }
    }

}
