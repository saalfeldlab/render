package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.util.List;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.validator.TemTileSpecValidator;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for adding a transform to all tiles in one or more sections of a stack.
 *
 * @author Eric Trautman
 */
public class TransformSectionClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Stack name", required = true)
        private String stack;

        @Parameter(names = "--transformId", description = "Identifier for tranformation", required = true)
        private String transformId;

        @Parameter(names = "--transformClass", description = "Name of transformation implementation (java) class", required = true)
        private String transformClass;

        // TODO: figure out less hacky way to handle spaces in transform data string
        @Parameter(names = "--transformData", description = "Data with which transformation implementation should be initialized (expects values to be separated by ',' instead of ' ')", required = true)
        private String transformData;

        @Parameter(names = "--disableValidation", description = "Disable flyTEM tile validation", required = false, arity = 0)
        private boolean disableValidation;

        @Parameter(description = "Z values", required = true)
        private List<String> zValues;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final TransformSectionClient client = new TransformSectionClient(parameters);
                for (final String z : parameters.zValues) {
                    client.generateStackDataForZ(new Double(z));
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;
    private final LeafTransformSpec stackTransform;

    private final RenderDataClient renderDataClient;

    public TransformSectionClient(final Parameters parameters) {

        this.parameters = parameters;

        this.stackTransform = new LeafTransformSpec(parameters.transformId,
                                                    null,
                                                    parameters.transformClass,
                                                    parameters.transformData.replace(',',' '));

        if (parameters.disableValidation) {
            this.tileSpecValidator = null;
        } else {
            this.tileSpecValidator = new TemTileSpecValidator();
        }

        this.renderDataClient = parameters.getClient();
    }

    public void generateStackDataForZ(final Double z)
            throws Exception {

        LOG.info("generateStackDataForZ: entry, z={}", z);

        final ResolvedTileSpecCollection tiles = renderDataClient.getResolvedTiles(parameters.stack, z);

        tiles.addTransformSpecToCollection(stackTransform);
        tiles.addReferenceTransformToAllTiles(stackTransform.getId());

        final int totalNumberOfTiles = tiles.getTileCount();
        if (tileSpecValidator != null) {
            tiles.setTileSpecValidator(tileSpecValidator);
            tiles.filterInvalidSpecs();
        }
        final int numberOfRemovedTiles = totalNumberOfTiles - tiles.getTileCount();

        LOG.info("generateStackDataForZ: added transform and derived bounding boxes for {} tiles with z of {}, removed {} bad tiles",
                 totalNumberOfTiles, z, numberOfRemovedTiles);

        renderDataClient.saveResolvedTiles(tiles, parameters.stack, z);

        LOG.info("generateStackDataForZ: exit, saved tiles and transforms for {}", z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TransformSectionClient.class);
}
