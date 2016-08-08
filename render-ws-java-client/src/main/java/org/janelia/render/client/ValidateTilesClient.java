package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.util.List;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for validating tiles in a stack.
 *
 * @author Eric Trautman
 */
public class ValidateTilesClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParametersWithValidator {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters
        // NOTE: --validatorClass and --validatorData parameters defined in RenderDataClientParametersWithValidator

        @Parameter(names = "--stack", description = "Stack name", required = true)
        private String stack;

        @Parameter(description = "Z values", required = true)
        private List<String> zValues;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, ValidateTilesClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ValidateTilesClient client = new ValidateTilesClient(parameters);
                for (final String z : parameters.zValues) {
                    client.validateTilesForZ(new Double(z));
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final RenderDataClient renderDataClient;

    public ValidateTilesClient(final Parameters parameters) {
        this.parameters = parameters;
        this.tileSpecValidator = parameters.getValidatorInstance();

        this.renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                     parameters.owner,
                                                     parameters.project);
    }

    public void validateTilesForZ(final Double z)
            throws Exception {

        LOG.info("validateTilesForZ: entry, z={}", z);

        final ResolvedTileSpecCollection tiles = renderDataClient.getResolvedTiles(parameters.stack, z);

        // resolve all tile specs before validating
        tiles.resolveTileSpecs();

        final int totalNumberOfTiles = tiles.getTileCount();
        if (tileSpecValidator != null) {
            tiles.setTileSpecValidator(tileSpecValidator);
            tiles.filterInvalidSpecs();
        }

        final int numberOfRemovedTiles = totalNumberOfTiles - tiles.getTileCount();

        LOG.info("validateTilesForZ: {} out of {} tiles for z {} are invalid",
                 numberOfRemovedTiles, totalNumberOfTiles, z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ValidateTilesClient.class);
}
