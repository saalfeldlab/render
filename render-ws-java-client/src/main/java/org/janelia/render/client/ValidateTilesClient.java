package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.List;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for validating tiles in a stack.
 *
 * @author Eric Trautman
 */
public class ValidateTilesClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();


        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--removeInvalidTiles",
                description = "If invalid tiles are found, remove them",
                arity = 0)
        public boolean removeInvalidTiles = false;

        @Parameter(
                names = "--completeStackAfterRemoval",
                description = "If invalid tiles have been removed, complete the stack after removal",
                arity = 0)
        public boolean completeStackAfterRemoval = false;

        @Parameter(
                description = "Z values",
                required = true)
        public List<String> zValues;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ValidateTilesClient client = new ValidateTilesClient(parameters);
                for (final String z : parameters.zValues) {
                    client.validateTilesForZ(new Double(z));
                }
                client.completeStackIfNecessary();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;
    private int totalTilesRemoved;

    private final RenderDataClient renderDataClient;

    private ValidateTilesClient(final Parameters parameters) {
        this.parameters = parameters;
        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();
        this.totalTilesRemoved = 0;

        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    private void validateTilesForZ(final Double z)
            throws Exception {

        LOG.info("validateTilesForZ: entry, z={}", z);

        final ResolvedTileSpecCollection tiles = renderDataClient.getResolvedTiles(parameters.stack, z);

        // resolve all tile specs before validating
        tiles.resolveTileSpecs();

        final int totalNumberOfTiles = tiles.getTileCount();
        if (tileSpecValidator != null) {
            tiles.setTileSpecValidator(tileSpecValidator);
            tiles.removeInvalidTileSpecs();
        }

        final int numberOfRemovedTiles = totalNumberOfTiles - tiles.getTileCount();

        if (parameters.removeInvalidTiles && (numberOfRemovedTiles > 0)) {
            if (totalTilesRemoved == 0) {
                renderDataClient.ensureStackIsInLoadingState(parameters.stack, null);
            }
            renderDataClient.deleteStack(parameters.stack, z);              // remove existing tiles for layer
            renderDataClient.saveResolvedTiles(tiles, parameters.stack, z); // and replace with good tiles
            totalTilesRemoved += numberOfRemovedTiles;
        }

        LOG.info("validateTilesForZ: {} out of {} tiles for z {} are invalid",
                 numberOfRemovedTiles, totalNumberOfTiles, z);
    }

    private void completeStackIfNecessary()
            throws IOException {
        if (parameters.completeStackAfterRemoval && (totalTilesRemoved > 0)) {
            renderDataClient.setStackState(parameters.stack, StackMetaData.StackState.COMPLETE);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ValidateTilesClient.class);
}
