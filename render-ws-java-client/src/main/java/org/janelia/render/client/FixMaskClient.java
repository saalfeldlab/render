package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client to update mask paths for all tile specs in a stack layer that have a "broken" mask.
 *
 * @author Eric Trautman
 */
public class FixMaskClient {

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
                names = "--targetProject",
                description = "Name of project to which updated tile specs should be written (default is source project)")
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of stack to which updated tile specs should be written (can be same as source stack)",
                required = true)
        private String targetStack;

        @Parameter(
                names = "--fixedMasksDirectory",
                description = "Path of directory containing all fixed (png) mask files",
                required = true)
        private String fixedMasksDirectory;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after fixing all layers",
                arity = 0)
        public boolean completeTargetStack = false;

        @Parameter(
                names = "--zValues",
                description = "Z values for filtering",
                required = false,
                variableArity = true)
        private List<String> zValues;

        public String getTargetProject() {
            return targetProject == null ? renderWeb.project : targetProject;
        }

        public String getTargetStack() {
            return targetStack == null ? stack : targetStack;
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final FixMaskClient client = new FixMaskClient(parameters);

                client.setUpDerivedStack();

                for (final String z : parameters.zValues) {
                    client.fixStackDataForZ(new Double(z));
                }

                if (parameters.completeTargetStack) {
                    client.completeTargetStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;
    private final Map<String, String> fixedMaskNamesToUrls;

    private FixMaskClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;
        this.sourceRenderDataClient = parameters.renderWeb.getDataClient();
        this.targetRenderDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                           parameters.renderWeb.owner,
                                                           parameters.getTargetProject());
        this.fixedMaskNamesToUrls = new HashMap<>();
        final Path fixedMasksDirectoryPath = Paths.get(parameters.fixedMasksDirectory).toAbsolutePath();
        Files.list(fixedMasksDirectoryPath)
                .filter(path -> path.toString().endsWith(".png"))
                .forEach(path -> fixedMaskNamesToUrls.put(path.getFileName().toString(), "file:" + path.toString()));

        if (this.fixedMaskNamesToUrls.size() == 0) {
            throw new IOException(fixedMasksDirectoryPath + " does not contain any fixed .png mask files");
        }
    }

    private void setUpDerivedStack() throws Exception {
        final StackMetaData fromStackMetaData = sourceRenderDataClient.getStackMetaData(parameters.stack);
        targetRenderDataClient.setupDerivedStack(fromStackMetaData, parameters.targetStack);
    }

    private void fixStackDataForZ(final Double z) throws Exception {

        LOG.info("fixStackDataForZ: entry, z={}", z);

        final ResolvedTileSpecCollection resolvedTiles = sourceRenderDataClient.getResolvedTiles(parameters.stack, z);

        if (! resolvedTiles.hasTileSpecs()) {
            throw new IllegalArgumentException("the " + parameters.renderWeb.project + " project " + parameters.stack +
                                               " stack does not have any tiles");
        }

        int fixCount = 0;
        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
            fixCount += fixTileSpec(tileSpec);
        }

        targetRenderDataClient.saveResolvedTiles(resolvedTiles, parameters.getTargetStack(), z);

        LOG.info("fixStackDataForZ: exit, fixed {} mask paths for z {}", fixCount, z);
    }

    private int fixTileSpec(final TileSpec tileSpec) {

        int fixCount = 0;

        final Integer zeroLevelKey = 0;

        for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {

            final Map.Entry<Integer, ImageAndMask> entry = channelSpec.getFirstMipmapEntry();

            if ((entry != null) && zeroLevelKey.equals(entry.getKey())) {

                final ImageAndMask sourceImageAndMask = entry.getValue();

                if (sourceImageAndMask.hasMask()) {

                    final File originalMaskFile = new File(sourceImageAndMask.getMaskFilePath());
                    final String fixedMaskUrl = fixedMaskNamesToUrls.get(originalMaskFile.getName());

                    if (fixedMaskUrl != null) {
                        final ImageAndMask updatedImageAndMask = new ImageAndMask(sourceImageAndMask.getImageUrl(),
                                                                                  fixedMaskUrl);
                        channelSpec.putMipmap(zeroLevelKey, updatedImageAndMask);
                        fixCount++;
                    }

                }

            }

        }

        return fixCount;
    }

    private void completeTargetStack()
            throws IOException {
        targetRenderDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(FixMaskClient.class);
}
