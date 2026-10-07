package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.FilterReplacementParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for replacing the filter specs of each tile in a stack with the filter specs
 * of the same tiles in another stack (e.g. to intensity correct SOFIMA aligned stacks starting from
 * filters that were not changed by 2D intensity correction).
 *
 * <p>Tiles are matched by tile id within each layer, so the tile stack must have the same tile ids
 * and z values as its filter stack.  Tiles with replaced filters are saved to a new target stack so
 * that the tile stack is left unchanged.</p>
 */
public class FilterReplacementClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack with the tiles whose filters should be replaced",
                required = true)
        public String stack;

        @ParametersDelegate
        public FilterReplacementParameters filterReplacement = new FilterReplacementParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                LOG.info("runClient: entry, parameters={}", parameters);

                parameters.filterReplacement.validate();

                final FilterReplacementClient client = new FilterReplacementClient();
                final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
                client.replaceFilters(dataClient,
                                      parameters.stack,
                                      parameters.filterReplacement);
            }
        };
        clientRunner.run();
    }

    public FilterReplacementClient() {
    }

    /**
     * Saves each layer of the specified tile stack to its target stack with every tile's filter spec
     * replaced by the filter spec of the same tile in its filter stack, and then completes the target stack.
     *
     * @param  dataClient         client for the tile stack's owner and project.
     * @param  tileStack          stack with the tiles whose filters should be replaced.
     * @param  filterReplacement  parameters identifying the filter and target stacks.
     *
     * @throws IllegalArgumentException
     *   if the filter or target stack cannot be derived or
     *   if the filter stack is missing any of the tile stack's layers or tiles.
     *
     * @throws IOException
     *   if any request fails.
     */
    public void replaceFilters(final RenderDataClient dataClient,
                               final String tileStack,
                               final FilterReplacementParameters filterReplacement)
            throws IllegalArgumentException, IOException {

        final StackId tileStackId = new StackId(dataClient.getOwner(), dataClient.getProject(), tileStack);
        final StackId filterStackId = filterReplacement.getFilterStackId(tileStackId);
        final StackId targetStackId = filterReplacement.getTargetStackId(tileStackId);

        LOG.info("replaceFilters: entry, tileStack={}, filterStack={}, targetStack={}",
                 tileStackId.toDevString(), filterStackId.toDevString(), targetStackId.toDevString());

        final String filterStack = filterStackId.getStack();
        final String targetStack = targetStackId.getStack();

        final List<Double> tileZValues = dataClient.getStackZValues(tileStack);

        // check layers before creating the target stack so that a mismatched pair fails without leaving an empty stack
        final Set<Double> filterZValues = new HashSet<>(dataClient.getStackZValues(filterStack));
        final List<Double> missingZValues = new ArrayList<>();
        for (final Double z : tileZValues) {
            if (! filterZValues.contains(z)) {
                missingZValues.add(z);
            }
        }
        if (! missingZValues.isEmpty()) {
            throw new IllegalArgumentException("filter stack " + filterStackId.toDevString() + " is missing " +
                                               missingZValues.size() + " layer(s) of tile stack " +
                                               tileStackId.toDevString() + ", missing z values are " +
                                               missingZValues);
        }

        final StackMetaData tileStackMetaData = dataClient.getStackMetaData(tileStack);
        dataClient.setupDerivedStack(tileStackMetaData, targetStack);

        for (final Double z : tileZValues) {

            final ResolvedTileSpecCollection tiles = dataClient.getResolvedTiles(tileStack, z);
            final ResolvedTileSpecCollection filterTiles = dataClient.getResolvedTiles(filterStack, z);

            final List<String> missingTileIds = new ArrayList<>();
            for (final TileSpec tileSpec : tiles.getTileSpecs()) {
                final TileSpec filterTileSpec = filterTiles.getTileSpec(tileSpec.getTileId());
                if (filterTileSpec == null) {
                    missingTileIds.add(tileSpec.getTileId());
                } else {
                    tileSpec.setFilterSpec(filterTileSpec.getFilterSpec());
                }
            }
            if (! missingTileIds.isEmpty()) {
                throw new IllegalArgumentException("filter stack " + filterStackId.toDevString() + " is missing " +
                                                   missingTileIds.size() + " tile(s) of tile stack " +
                                                   tileStackId.toDevString() + " for z " + z +
                                                   ", missing tile ids are " + missingTileIds);
            }

            dataClient.saveResolvedTiles(tiles, targetStack, z);
        }

        dataClient.setStackState(targetStack, StackMetaData.StackState.COMPLETE);

        LOG.info("replaceFilters: exit, saved {} layer(s) to {}",
                 tileZValues.size(), targetStackId.toDevString());
    }

    private static final Logger LOG = LoggerFactory.getLogger(FilterReplacementClient.class);
}
