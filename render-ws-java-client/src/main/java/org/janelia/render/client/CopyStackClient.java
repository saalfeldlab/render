package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for copying tiles from one stack to another.
 *
 * @author Eric Trautman
 */
public class CopyStackClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--fromStack", description = "Name of source stack", required = true)
        private String fromStack;

        @Parameter(names = "--toStack", description = "Name of target stack", required = true)
        private String toStack;

        @Parameter(names = "--z", description = "Z value of section to be copied", required = true)
        private Double z;

        @Parameter(names = "--keepExisting",
                description = "Keep any existing target stack tiles with the specified z (default is to remove them)",
                required = false, arity = 0)
        private boolean keepExisting = false;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, CopyStackClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final CopyStackClient client = new CopyStackClient(parameters);

                client.copySection();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    public CopyStackClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.getClient();
    }

    public void copySection() throws Exception {

        final ResolvedTileSpecCollection sourceCollection =
                renderDataClient.getResolvedTiles(parameters.fromStack, parameters.z);

        sourceCollection.removeUnreferencedTransforms();

        if (! parameters.keepExisting) {
            renderDataClient.deleteStack(parameters.toStack, parameters.z);
        }

        renderDataClient.saveResolvedTiles(sourceCollection, parameters.toStack, parameters.z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyStackClient.class);
}
