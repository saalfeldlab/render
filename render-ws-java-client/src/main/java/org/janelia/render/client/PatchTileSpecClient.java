package org.janelia.render.client;

import java.io.IOException;
import java.util.List;

import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

/**
 * Java client for replacing specs for problem tiles with specs from (good) adjacent tiles.
 *
 * @author Eric Trautman
 */
public class PatchTileSpecClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(names = "--stack", description = "Stack name", required = true)
        public String stack;

        @Parameter(
                names = "--sourceTileId",
                description = "Identifies tile(s) with valid patch image(s)",
                required = true,
                variableArity = true)
        public List<String> sourceTileIds;

        @Parameter(
                names = "--targetTileId",
                description = "Identifies tile(s) to be patched",
                required = true,
                variableArity = true)
        public List<String> targetTileIds;

        @Parameter(
                names = "--targetZ",
                description = "Identifies z value(s) for patched tile(s)",
                required = true,
                variableArity = true)
        public List<Double> targetZValues;

        @Parameter(
                names = "--targetSectionId",
                description = "Identifies sectionId value(s) for patched tile(s)",
                required = true,
                variableArity = true)
        public List<String> targetSectionIds;

        @Parameter(
                names = "--completeStack",
                description = "Complete the stack after patching",
                arity = 0)
        public boolean completeStack = false;

    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final PatchTileSpecClient client = new PatchTileSpecClient(parameters);
                client.patchTiles();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    private PatchTileSpecClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    private void patchTiles()
            throws IOException, IllegalArgumentException {

        if (parameters.sourceTileIds.size() < 1) {
            throw new IllegalArgumentException("at least one sourceTileId must be specified");
        }

        if (parameters.sourceTileIds.size() != parameters.targetTileIds.size()) {
            throw new IllegalArgumentException("same number of sourceTileId and targetTileId values must be specified");
        } else if (parameters.sourceTileIds.size() != parameters.targetZValues.size()) {
            throw new IllegalArgumentException("same number of sourceTileId and targetZ values must be specified");
        } else if (parameters.sourceTileIds.size() != parameters.targetSectionIds.size()) {
            throw new IllegalArgumentException("same number of sourceTileId and targetSectionId values must be specified");
        }

        renderDataClient.ensureStackIsInLoadingState(parameters.stack, null);

        final ResolvedTileSpecCollection patchedTileSpecs = new ResolvedTileSpecCollection();
        for (int i = 0; i < parameters.sourceTileIds.size(); i++) {
            final String sourceTileId = parameters.sourceTileIds.get(i);
            final TileSpec tileSpec = renderDataClient.getTile(parameters.stack, sourceTileId);
            tileSpec.setTileId(parameters.targetTileIds.get(i));
            tileSpec.setZ(parameters.targetZValues.get(i));
            final LayoutData layout = tileSpec.getLayout();
            tileSpec.setLayout(new LayoutData(parameters.targetSectionIds.get(i),
                                              layout.getTemca(),
                                              layout.getCamera(),
                                              layout.getImageRow(),
                                              layout.getImageCol(),
                                              layout.getStageX(),
                                              layout.getStageY(),
                                              layout.getRotation(),
                                              layout.getPixelsize(),
                                              layout.getDistanceZ()));
            tileSpec.addLabel("patched");
            patchedTileSpecs.addTileSpecToCollection(tileSpec);
        }

        renderDataClient.saveResolvedTiles(patchedTileSpecs, parameters.stack, null);

        if (parameters.completeStack) {
            renderDataClient.setStackState(parameters.stack, StackMetaData.StackState.COMPLETE);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(PatchTileSpecClient.class);
}
