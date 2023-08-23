package org.janelia.render.client.stack;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.loader.ImageLoader.LoaderType;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client to create 16-bit version of an 8-bit aligned stack.
 *
 * @author Eric Trautman
 */
public class Create16BitH5StackClient {

    @SuppressWarnings("ALL")
    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--alignStack",
                description = "Name of aligned stack from which tile specs with 8-bit h5 image paths should be read",
                required = true)
        public String alignStack;

        @Parameter(
                names = "--rawStack",
                description = "Name of raw stack to which tile specs with 16-bit h5 image paths should be written",
                required = true)
        public String rawStack;

        @Parameter(
                names = "--rawRootDirectory",
                description = "Path of root directory containing 16-bit .raw-archive.h5 files " +
                              "(e.g. /nrs/cellmap/data/aic_desmosome-2/raw).  " +
                              "Files are assumed to have standard subdirectories and names " +
                              "(e.g. Gemini450-0113/2021/01/27/02/Gemini450-0113_21-01-27_025406.raw-archive.h5).",
                required = true)
        public String rawRootDirectory;

        @Parameter(
                names = "--completeRawStack",
                description = "Complete the raw stack after saving all z layers",
                arity = 0)
        public boolean completeRawStack = false;

        @Parameter(
                names = "--z",
                description = "Z values of layers to copy (omit to copy all z layers)",
                required = false,
                variableArity = true)
        public List<Double> zValues;

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--skipFileValidation",
                description = "Skip check for existence of .raw-archive.h5 files",
                arity = 0)
        public boolean skipFileValidation = false;
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final Create16BitH5StackClient client = new Create16BitH5StackClient(parameters);

                final RenderDataClient renderDataClient = parameters.renderWeb.getDataClient();
                final StackMetaData fromStackMetaData = renderDataClient.getStackMetaData(parameters.alignStack);

                renderDataClient.setupDerivedStack(fromStackMetaData, parameters.rawStack);

                // remove mipmap path builder because 16-bit mipmaps don't exist
                renderDataClient.deleteMipmapPathBuilder(parameters.rawStack);

                final List<Double> zValues = renderDataClient.getStackZValues(parameters.alignStack,
                                                                              parameters.layerRange.minZ,
                                                                              parameters.layerRange.maxZ,
                                                                              parameters.zValues);
                for (final Double z : zValues) {
                    client.copyZ(renderDataClient, z);
                }

                if (parameters.completeRawStack) {
                    renderDataClient.setStackState(parameters.rawStack, StackMetaData.StackState.COMPLETE);
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    protected Create16BitH5StackClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private void copyZ(final RenderDataClient renderDataClient,
                       final Double z) throws Exception {

        LOG.info("copyZ: entry, z={}", z);

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(parameters.alignStack, z);
        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
            updateTileSpec(tileSpec);
        }

        renderDataClient.saveResolvedTiles(resolvedTiles, parameters.rawStack, z);

        LOG.info("copyZ: exit");
    }

    private void updateTileSpec(final TileSpec tileSpec)
            throws IllegalStateException {

        final Integer zeroLevelKey = 0;
        final List<ChannelSpec> channelSpecList = tileSpec.getAllChannels();
        int updatedUrlCount = 0;

        for (final ChannelSpec channelSpec : channelSpecList) {

            final Map.Entry<Integer, ImageAndMask> entry = channelSpec.getFirstMipmapEntry();

            if ((entry != null) && zeroLevelKey.equals(entry.getKey())) {
                final ImageAndMask sourceImageAndMask = entry.getValue();
                if (sourceImageAndMask.hasImage()) {

                    final LoaderType loaderType = sourceImageAndMask.getImageLoaderType();
                    String rawSourceUrl = null;

                    if (LoaderType.H5_SLICE.equals(loaderType)) {
                        rawSourceUrl = buildRawSourceUrlForH5(tileSpec.getTileId(),
                                                              sourceImageAndMask.getImageUrl());
                    } else if (LoaderType.IMAGEJ_DEFAULT.equals(loaderType)) {
                        rawSourceUrl = buildRawSourceUrlForInLens(tileSpec.getTileId(),
                                                                  sourceImageAndMask.getImageUrl());
                    }

                    if (rawSourceUrl != null) {
                        final LoaderType sourceMaskLoaderType = sourceImageAndMask.getMaskLoaderType();
                        final LoaderType maskLoaderType =
                                LoaderType.IMAGEJ_DEFAULT.equals(sourceMaskLoaderType) ? null : sourceMaskLoaderType;
                        final ImageAndMask updatedImageAndMask =
                                new ImageAndMask(rawSourceUrl,
                                                 LoaderType.H5_SLICE,
                                                 null,
                                                 sourceImageAndMask.getMaskUrl(),
                                                 maskLoaderType,
                                                 sourceImageAndMask.getMaskSliceNumber());
                        channelSpec.putMipmap(zeroLevelKey, updatedImageAndMask);
                        
                        updatedUrlCount++;
                    }
                }

            } else {
                throw new IllegalStateException("first mipmap for tile " + tileSpec.getTileId() + " is not level zero");
            }

            tileSpec.setMinAndMaxIntensity(0.0, 65535.0, channelSpec.getName());
        }

        if (updatedUrlCount != channelSpecList.size()) {
            throw new IllegalStateException("expected " + channelSpecList.size() + " instead of " + updatedUrlCount +
                                            " paths to be updated for tile " + tileSpec.getTileId());
        }
    }

    protected String buildRawSourceUrlForH5(final String tileId,
                                            final String alignSourceUrl)
            throws IllegalStateException {
        // From:
        //   file:///nrs/cellmap/data/jrc_mus-hippocampus-1/align/Merlin-6049/2023/06/07/11/Merlin-6049_23-06-07_110544.uint8.h5?dataSet=/0-0-0/mipmap.0&z=0
        // To:
        //   file:///nrs/cellmap/data/jrc_mus-hippocampus-1/raw/Merlin-6049/2023/06/07/11/Merlin-6049_23-06-07_110544.raw-archive.h5?dataSet=/0-0-0/c0&z=0
        final String rawUrl;
        final Matcher m = H5_PATTERN.matcher(alignSourceUrl);
        if (m.matches()) {
            final String fileName = m.group(2) + ".raw-archive.h5";
            final File file = Paths.get(parameters.rawRootDirectory, m.group(1), fileName).toAbsolutePath().toFile();
            if (parameters.skipFileValidation || file.exists()) {
                rawUrl = "file://" + file + "?dataSet=" + m.group(3) + "/c0"; // need to exclude z=0 parameter
            }  else {
                throw new IllegalStateException(file + " not found for tile " + tileId);
            }
        } else {
            throw new IllegalStateException("cannot parse source URL for tile " + tileId + ": " + alignSourceUrl);
        }
        return rawUrl;
    }

    protected String buildRawSourceUrlForInLens(final String tileId,
                                                final String alignSourceUrl)
            throws IllegalStateException {

        // From:
        //   file:///nrs/cellmap/data/aic_desmosome-2/InLens/Gemini450-0113_21-01-27_030921_0-0-0-InLens.png
        // To:
        //   file:///nrs/cellmap/data/aic_desmosome-2/raw/Gemini450-0113/2021/01/27/03/Gemini450-0113_21-01-27_030921.raw-archive.h5?dataSet=/0-0-0/c0&z=0

        final String rawUrl;
        final Matcher m = INLENS_PATTERN.matcher(alignSourceUrl);
        if (m.matches()) {
            final String fileName = m.group(1) + ".raw-archive.h5";
            final File file = Paths.get(parameters.rawRootDirectory,
                                        m.group(2),
                                        "20" + m.group(3),
                                        m.group(4),
                                        m.group(5),
                                        m.group(6),
                                        fileName).toAbsolutePath().toFile();
            if (parameters.skipFileValidation || file.exists()) {
                rawUrl = "file://" + file + "?dataSet=/" + m.group(7) + "/c0"; // need to exclude z=0 parameter
            }  else {
                throw new IllegalStateException(file + " not found for tile " + tileId);
            }
        } else {
            throw new IllegalStateException("cannot parse source URL for tile " + tileId + ": " + alignSourceUrl);
        }

        return rawUrl;
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(Create16BitH5StackClient.class);

    // file:///nrs/cellmap/data/jrc_mus-hippocampus-1/align/Merlin-6049/2023/06/07/11/Merlin-6049_23-06-07_110544.uint8.h5?dataSet=/0-0-0/mipmap.0&z=0
    private static final Pattern H5_PATTERN =
            Pattern.compile(".*/align/(.*/\\d{4}/\\d{2}/\\d{2}/\\d{2})/(.*).uint8.h5.dataSet=(.*)/mipmap.0.*");

    // file:/nrs/cellmap/data/aic_desmosome-2/InLens/Gemini450-0113_21-01-27_030921_0-0-0-InLens.png
    private static final Pattern INLENS_PATTERN =
            Pattern.compile(".*/InLens/((.*)_(\\d{2})-(\\d{2})-(\\d{2})_(\\d{2})\\d{4})_(\\d-\\d-\\d)-InLens.*");
}
