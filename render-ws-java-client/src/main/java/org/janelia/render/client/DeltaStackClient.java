package org.janelia.render.client;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.APPEND;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.trakem2.transform.TranslationModel2D;

/**
 * Java client for copying the tiles from one stack that are not in another stack to a third stack.
 *
 * @author Eric Trautman
 */
public class DeltaStackClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--fromStack",
                description = "Name of source stack",
                required = true)
        public String fromStack;

        @Parameter(
                names = "--toOwner",
                description = "Name of target stack owner (default is same as source stack owner)"
        )
        public String toOwner;

        @Parameter(
                names = "--toProject",
                description = "Name of target stack project (default is same as source stack project)"
        )
        public String toProject;

        @Parameter(
                names = "--toStack",
                description = "Name of target stack",
                required = true)
        public String toStack;

        @Parameter(
                names = "--z",
                description = "Z value of layer to be copied (omit to copy all layers)",
                variableArity = true)
        public List<Double> zValues;

        @Parameter(
                names = "--consolidateForReview",
                description = "Indicates that tiles should be positioned close together to simplify a review process (default is to keep source location)",
                arity = 0)
        public boolean consolidateForReview = false;

        @Parameter(
                names = "--excludeTileIdsInStacks",
                description = "Name(s) of stack(s) that contain ids of tiles to be excluded from target stack (assumes owner and project are same as source stack).",
                variableArity = true,
                required = true
        )
        public List<String> excludeTileIdsInStacks;

        @ParametersDelegate
        LayerBoundsParameters layerBounds = new LayerBoundsParameters();

        @Parameter(
                names = "--completeToStackAfterCopy",
                description = "Complete the to stack after copying all layers",
                arity = 0)
        public boolean completeToStackAfterCopy = false;

        String getToOwner() {
            if (toOwner == null) {
                toOwner = renderWeb.owner;
            }
            return toOwner;
        }

        String getToProject() {
            if (toProject == null) {
                toProject = renderWeb.project;
            }
            return toProject;
        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.layerBounds.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final DeltaStackClient client = new DeltaStackClient(parameters);

                client.setUpDerivedStack();

                client.copyLayers();

                if (parameters.completeToStackAfterCopy) {
                    client.completeToStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient fromDataClient;
    private final RenderDataClient toDataClient;
    private final List<Double> zValues;

    private DeltaStackClient(final Parameters parameters) throws Exception {

        this.parameters = parameters;

        this.fromDataClient = parameters.renderWeb.getDataClient();

        this.toDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                 parameters.getToOwner(),
                                                 parameters.getToProject());

        if ((parameters.zValues == null) || (parameters.zValues.size() == 0)) {
            this.zValues = fromDataClient.getStackZValues(parameters.fromStack);
        } else {
            this.zValues = parameters.zValues;
        }

    }

    private void setUpDerivedStack() throws Exception {
        final StackMetaData fromStackMetaData = fromDataClient.getStackMetaData(parameters.fromStack);
        toDataClient.setupDerivedStack(fromStackMetaData, parameters.toStack);
    }

    private void completeToStack() throws Exception {
        toDataClient.setStackState(parameters.toStack, StackState.COMPLETE);
    }

    private void copyLayers()
            throws Exception {
        for (final Double z : zValues) {
            copyLayer(z);
        }
    }

    private void copyLayer(final Double z) throws Exception {

        final ResolvedTileSpecCollection sourceCollection =
                fromDataClient.getResolvedTiles(parameters.fromStack, z);

        final Set<String> tileIdsToRemove = new HashSet<>();
        for (final String tileIdStack : parameters.excludeTileIdsInStacks) {
            tileIdsToRemove.addAll(
                    toDataClient.getTileBounds(tileIdStack, z)
                            .stream()
                            .map(TileBounds::getTileId)
                            .collect(Collectors.toList()));
        }



        if (tileIdsToRemove.size() > 0) {
            final int numberOfTilesBeforeFilter = sourceCollection.getTileCount();
            sourceCollection.removeTileSpecs(tileIdsToRemove);
            final int numberOfTilesRemoved = numberOfTilesBeforeFilter - sourceCollection.getTileCount();
            LOG.info("copyLayer: removed {} tiles found in stack(s) {}",
                     numberOfTilesRemoved, parameters.excludeTileIdsInStacks);
        }

        if (sourceCollection.hasTileSpecs()) {

            if (parameters.consolidateForReview) {
                consolidateForReview(sourceCollection);
            }

            sourceCollection.removeUnreferencedTransforms();

            toDataClient.saveResolvedTiles(sourceCollection, parameters.toStack, z);

        }  else {
            LOG.info("copyLayer: nothing to do, there are no delta tiles for z {}", z);
        }
    }

    private void consolidateForReview(final ResolvedTileSpecCollection sourceCollection) {

        final int tileCount = sourceCollection.getTileCount();
        final int numberOfColumns = (int) Math.ceil(Math.sqrt(tileCount));
        final int margin = 100;

        int tileSpecCount = 0;
        int y = 0;
        int x = 0;
        int maxHeightForRow = 0;

        final List<String> sortedTileIds =
                sourceCollection.getTileIds().stream().sorted().collect(Collectors.toList());

        for (final String tileId : sortedTileIds) {

            final TileSpec tileSpec = sourceCollection.getTileSpec(tileId);

            if ((tileSpecCount > 0) && (tileSpecCount % numberOfColumns == 0)) {
                x = 0;
                y = y + maxHeightForRow + margin;
                maxHeightForRow = 0;
            }

            // remove all transforms from the tile spec
            tileSpec.setTransforms(new ListTransformSpec());

            // then add "consolidation" transform
            final String dataString = x + " " + y;
            final TransformSpec transformSpec = new LeafTransformSpec(TranslationModel2D.class.getName(),
                                                                      dataString);

            sourceCollection.addTransformSpecToTile(tileSpec.getTileId(),
                                                    transformSpec,
                                                    APPEND);

            x = x + tileSpec.getWidth() + margin;
            maxHeightForRow = Math.max(tileSpec.getHeight(), maxHeightForRow);
            tileSpecCount++;

        }

        LOG.info("consolidateForReview: exit, updated transforms for {} tiles",
                 tileSpecCount);
    }

    private static final Logger LOG = LoggerFactory.getLogger(DeltaStackClient.class);
}
