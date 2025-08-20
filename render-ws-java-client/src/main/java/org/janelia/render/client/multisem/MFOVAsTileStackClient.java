package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
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

        @Parameter(
                names = "--mfovTileRenderScale",
                description = "Scale for rendered SFOVs when creating the MFOV tiles.  " +
                              "The default value of 0.1 works well because MFOVs have 91 SFOVs, " +
                              "so each MFOV tile image will be roughly the same size as an original SFOV.",
                required = true)
        public Double mfovTileRenderScale = 0.1;

        @Parameter(
                names = "--mfovTileStackSuffix",
                description = "Suffix to append to the source stack name when creating the MFOV as tile stack name.")
        public String mfovTileStackSuffix = "_mt";
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
                                    parameters.mfovTileRenderScale,
                                    "",
                                    parameters.mfovTileStackSuffix);
        }
    }

    /**
     * Builds an MFOV as tile stack for the specified stack with Z values.
     *
     * @param  stackWithZ           identifies the source stack.
     * @param  renderDataClient     web service client for render stack data.
     * @param  mfovTileRenderScale  scale for rendered SFOVs when creating the MFOV tiles.
     * @param  mfovTileStackSuffix  suffix to append to the source stack name when creating the MFOV as tile stack name.
     *
     * @return the identifier of the newly created MFOV as tile stack.
     *
     * @throws IOException
     *   if the build fails for any reason.
     */
    public static StackId buildOneMFOVAsTileStack(final StackWithZValues stackWithZ,
                                                  final RenderDataClient renderDataClient,
                                                  final Double mfovTileRenderScale,
                                                  final String sourceStackSuffix,
                                                  final String mfovTileStackSuffix)
            throws IOException, IllegalStateException {

        final StackId sourceStackId = stackWithZ.getStackId().withStackSuffix(sourceStackSuffix);
        final String sourceStackName = sourceStackId.getStack();
        final StackId mfovAsTileStackId = stackWithZ.getStackId().withStackSuffix(mfovTileStackSuffix);

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
                                                                   mfovTileRenderScale));
            }

            final ResolvedTileSpecCollection resolvedTiles = new ResolvedTileSpecCollection(mfovTileSpecs);
            renderDataClient.saveResolvedTiles(resolvedTiles, mfovAsTileStackName, z);
        }

        renderDataClient.setStackState(mfovAsTileStackName, StackMetaData.StackState.COMPLETE);

        return mfovAsTileStackId;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MFOVAsTileStackClient.class);
}
