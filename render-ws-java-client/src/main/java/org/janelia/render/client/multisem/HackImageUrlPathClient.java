package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.ImageAndMask;
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
 *
 * @author Eric Trautman
 */
public class HackImageUrlPathClient {

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

                final HackImageUrlPathClient client = new HackImageUrlPathClient(parameters);
                client.fixStackData();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private final RenderDataClient renderDataClient;

    private HackImageUrlPathClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    private void fixStackData() throws Exception {
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

            if ((entry != null) && zeroLevelKey.equals(entry.getKey())) {

                final ImageAndMask sourceImageAndMask = entry.getValue();

                // file:/nrs/hess/data/hess_wafer_53/raw/imaging/msem/scan_001/wafer_53_scan_001_20220427_23-16-30/402_/000005/402_000005_001_2022-04-28T1457426331720.png
                final String imageUrl = sourceImageAndMask.getImageUrl();

                final Matcher m = PATH_PATTERN.matcher(imageUrl);
                if (m.matches()) {

                    // file:/nrs/hess/data/hess_wafer_53/msem_with_hayworth_contrast/scan_001/402_/000005/402_000005_001_2022-04-28T1457426331720.png
                    final File hackFile = new File("/nrs/hess/data/hess_wafer_53/msem_with_hayworth_contrast/" +
                                                   m.group(1) +  m.group(2));
                    if (! hackFile.exists()) {
                        throw new IllegalArgumentException("file does not exist: " + hackFile);
                    }

                    final ImageAndMask hackedImageAndMask =
                            sourceImageAndMask.copyWithDerivedUrls("file:" + hackFile.getAbsolutePath(),
                                                                   sourceImageAndMask.getMaskUrl());

                    channelSpec.putMipmap(zeroLevelKey, hackedImageAndMask);

                } else {
                    throw new IllegalArgumentException("invalid image URL: " + imageUrl);
                }

            }

        }

    }

    private final Pattern PATH_PATTERN = Pattern.compile("^file:/nrs.*/(scan_\\d\\d\\d/)wafer.*/(\\d\\d\\d_/.*png)$");

    private static final Logger LOG = LoggerFactory.getLogger(HackImageUrlPathClient.class);
}
