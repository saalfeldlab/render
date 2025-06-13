package org.janelia.render.client.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.multisem.MultiSemMfovColumn;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MFOVAsTileParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client that creates tile specs for each MFOV, saving the MFOV tile specs to a new stack.
 * Each MFOV tile spec is created by aggregating its downsampled SFOVs into a single tile.
 */
public class MFOVAsTileStackClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public MFOVAsTileParameters mfovAsTile = new MFOVAsTileParameters();
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MFOVAsTileStackClient client = new MFOVAsTileStackClient(parameters);
                client.buildAllMFOVAsTileStacks();

                LOG.info("runClient: exit");
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;


    public MFOVAsTileStackClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    /**
     * Builds MFOV as tile stacks for all stacks identified in the parameters.
     *
     * @throws IOException
     *   if the build fails for any reason.
     */
    public void buildAllMFOVAsTileStacks()
            throws IOException, IllegalStateException {

        final RenderDataClient renderDataClient = parameters.multiProject.getDataClient();
        final List<StackWithZValues> stackWithZList = parameters.multiProject.buildListOfStackWithAllZ();

        for (final StackWithZValues stackWithZ : stackWithZList) {
            buildOneMFOVAsTileStack(stackWithZ,
                                    renderDataClient,
                                    parameters.mfovAsTile);
        }
    }

    /**
     * Builds an MFOV as tile stack for the specified stack with Z values.
     *
     * @param  stackWithZ        identifies the source stack.
     * @param  renderDataClient  web service client for render stack data.
     * @param  matParameters     MFOV as tile parameters.
     *
     * @throws IOException
     *   if the build fails for any reason.
     */
    public static void buildOneMFOVAsTileStack(final StackWithZValues stackWithZ,
                                               final RenderDataClient renderDataClient,
                                               final MFOVAsTileParameters matParameters)
            throws IOException, IllegalStateException {

        final StackId sourceStackId = stackWithZ.getStackId();
        final String sourceStackName = sourceStackId.getStack();
        final StackId mfovAsTileStackId = stackWithZ.getStackId().withStackSuffix(matParameters.mfovTileStackSuffix);

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(sourceStackName);
        final String mfovAsTileStackName = mfovAsTileStackId.getStack();

        // TODO: fix mfovAsTileStack metadata to reflect actual pixel resolution
        renderDataClient.setupDerivedStack(stackMetaData,
                                           mfovAsTileStackName);

        for (final Double z : stackWithZ.getzValues()) {

            final List<TileBounds> sourceTileBoundsListForZLayer =
                    renderDataClient.getTileBounds(sourceStackId.getStack(), z);

            final List<MultiSemMfovColumn> mfovColumnList =
                    MultiSemMfovColumn.assembleColumnData(z, sourceTileBoundsListForZLayer);

            final List<TileSpec> mfovTileSpecs = new ArrayList<>();
            for (final MultiSemMfovColumn mfovColumn : mfovColumnList) {
                mfovTileSpecs.addAll(mfovColumn.buildMfovTileSpecs(renderDataClient.getBaseDataUrl(),
                                                                   sourceStackId,
                                                                   matParameters.mfovTileRenderScale));
            }

            final ResolvedTileSpecCollection resolvedTiles = new ResolvedTileSpecCollection(mfovTileSpecs);
            renderDataClient.saveResolvedTiles(resolvedTiles, mfovAsTileStackName, z);
        }

        renderDataClient.setStackState(mfovAsTileStackName, StackMetaData.StackState.COMPLETE);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVAsTileStackClient.class);
}
