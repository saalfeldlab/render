package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for updating section z values.
 *
 * @author Eric Trautman
 */
public class SectionUpdateClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(names = "--stack", description = "Stack name", required = true)
        public String stack;

        @Parameter(names = "--sectionId", description = "Section ID", required = true)
        public String sectionId;

        @Parameter(names = "--z", description = "Z value", required = true)
        public Double z;

        @Parameter(
                names = "--completeToStackAfterUpdate",
                description = "Complete the stack after updating z values",
                arity = 0)
        public boolean completeToStackAfterUpdate = false;

        @Parameter(
                names = "--minX",
                description = "Minimum X value for all tiles to be updated"
        )
        public Double minX;

        @Parameter(
                names = "--minY",
                description = "Minimum Y value for all tiles to be updated"
        )
        public Double minY;

        @Parameter(
                names = "--maxX",
                description = "Maximum X value for all tiles to be updated"
        )
        public Double maxX;

        @Parameter(
                names = "--maxY",
                description = "Maximum Y value for all tiles to be updated"
        )
        public Double maxY;

        private boolean isBoxSpecified() {
            return ((minX != null) && (minY != null) && (maxX != null) && (maxY != null));
        }
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

                final SectionUpdateClient client = new SectionUpdateClient(parameters);
                client.updateZ();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    private SectionUpdateClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    private void updateZ()
            throws Exception {

        final String stack = parameters.stack;
        final String sectionId = parameters.sectionId;

        renderDataClient.ensureStackIsInLoadingState(stack, null);

        if (parameters.isBoxSpecified()) {

            final List<SectionData> sectionDataList = renderDataClient.getStackSectionData(stack, null, null);

            Double zForSectionId = null;
            for (final SectionData sectionData : sectionDataList) {
                if (sectionId.equals(sectionData.getSectionId())) {
                    zForSectionId = sectionData.getZ();
                    break;
                }
            }

            if (zForSectionId == null) {
                throw new IllegalArgumentException("sectionId '" + sectionId + "' not found in stack " + stack);
            }

            final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, zForSectionId);
            final TileBoundsRTree tree = new TileBoundsRTree(zForSectionId, tileBoundsList);
            final Set<String> updateTileIds = new HashSet<>(tileBoundsList.size() * 2);
            for (final TileBounds tileBounds : tree.findTilesInBox(parameters.minX, parameters.minY,
                                                                   parameters.maxX, parameters.maxY)) {
                if (sectionId.equals(tileBounds.getSectionId())) {
                    updateTileIds.add(tileBounds.getTileId());
                }
            }

            if (updateTileIds.size() == 0) {
                throw new IllegalArgumentException("no tile specs exist for stack " + stack + " in the bounding box: " +
                                                   "min (" + parameters.minX + ", " + parameters.minY +
                                                   "), max (" + parameters.maxX + ", " + parameters.maxY  + ")");
            }

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, zForSectionId);

            resolvedTiles.removeDifferentTileSpecs(updateTileIds);


            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                tileSpec.setZ(parameters.z);
            }

            renderDataClient.saveResolvedTiles(resolvedTiles, stack, null);

        } else {
            renderDataClient.updateZForSection(stack, parameters.sectionId, parameters.z);
        }

        if (parameters.completeToStackAfterUpdate) {
            renderDataClient.setStackState(stack, StackMetaData.StackState.COMPLETE);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(SectionUpdateClient.class);
}
