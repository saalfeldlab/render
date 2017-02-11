package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.spec.stack.StackVersion;
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

        @Parameter(
                names = "--toOwner",
                description = "Name of target stack owner (default is same as source stack owner)",
                required = false)
        private String toOwner;

        @Parameter(
                names = "--toProject",
                description = "Name of target stack project (default is same as source stack project)",
                required = false)
        private String toProject;

        @Parameter(names = "--toStack", description = "Name of target stack", required = true)
        private String toStack;

        @Parameter(names = "--z", description = "Z value of section to be copied", required = true)
        private List<Double> zValues;

        @Parameter(names = "--minX", description = "Minimum X value for all tiles", required = false)
        private Double minX;

        @Parameter(names = "--maxX", description = "Maximum X value for all tiles", required = false)
        private Double maxX;

        @Parameter(names = "--minY", description = "Minimum Y value for all tiles", required = false)
        private Double minY;

        @Parameter(names = "--maxY", description = "Maximum Y value for all tiles", required = false)
        private Double maxY;

        @Parameter(
                names = "--keepExisting",
                description = "Keep any existing target stack tiles with the specified z (default is to remove them)",
                required = false, arity = 0)
        private boolean keepExisting = false;

        @Parameter(
                names = "--completeToStackAfterCopy",
                description = "Complete the to stack after copying all layers",
                required = false, arity = 0)
        private boolean completeToStackAfterCopy = false;

        public String getToOwner() {
            if (toOwner == null) {
                toOwner = owner;
            }
            return toOwner;
        }

        public String getToProject() {
            if (toProject == null) {
                toProject = project;
            }
            return toProject;
        }

        public void validateStackBounds() throws IllegalArgumentException {

            if ((minX != null) || (maxX != null) || (minY != null) || (maxY != null)) {

                if ((minX == null) || (maxX == null) || (minY == null) || (maxY == null)) {
                    throw new IllegalArgumentException("since one or more of minX (" + minX + "), maxX (" + maxX +
                                                       "), minY (" + minY + "), maxY (" + maxY +
                                                       ") is specified, all must be specified");
                }

                if (minX > maxX) {
                    throw new IllegalArgumentException("minX (" + minX + ") is greater than maxX (" + maxX + ")");
                }

                if (minY > maxY) {
                    throw new IllegalArgumentException("minY (" + minY + ") is greater than maxY (" + maxY + ")");
                }
            }

        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, CopyStackClient.class);
                parameters.validateStackBounds();

                LOG.info("runClient: entry, parameters={}", parameters);

                final CopyStackClient client = new CopyStackClient(parameters);

                client.setUpToStack();

                for (final Double z : parameters.zValues) {
                    client.copyLayer(z);
                }

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

    public CopyStackClient(final Parameters parameters) throws Exception {

        this.parameters = parameters;

        this.fromDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                   parameters.owner,
                                                   parameters.project);

        this.toDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                 parameters.getToOwner(),
                                                 parameters.getToProject());
    }

    public void setUpToStack() throws Exception {

        StackState toStackState = null;
        boolean isLoading = true;
        try {
            final StackMetaData toStackMetaData = toDataClient.getStackMetaData(parameters.toStack);
            isLoading = toStackMetaData.isLoading();
            toStackState = toStackMetaData.getState();
        } catch (final Throwable t) {
            LOG.info("to stack does not exist, creating it ...");
            final StackMetaData fromStackMetaData = fromDataClient.getStackMetaData(parameters.fromStack);
            final StackVersion sourceVersion = fromStackMetaData.getCurrentVersion();
            final StackVersion targetVerison = new StackVersion(new Date(),
                                                                "copied from " + fromStackMetaData.getStackId(),
                                                                null,
                                                                null,
                                                                sourceVersion.getStackResolutionX(),
                                                                sourceVersion.getStackResolutionY(),
                                                                sourceVersion.getStackResolutionZ(),
                                                                null,
                                                                sourceVersion.getMipmapPathBuilder());
            toDataClient.saveStackVersion(parameters.toStack, targetVerison);
        }

        if (! isLoading) {
            LOG.info("{} stack state is {}, will try to set it back to LOADING ...", parameters.toStack, toStackState);
            toDataClient.setStackState(parameters.toStack, StackState.LOADING);
        }

    }

    public void completeToStack() throws Exception {
        toDataClient.setStackState(parameters.toStack, StackState.COMPLETE);
    }

    public void copyLayer(final Double z) throws Exception {

        final ResolvedTileSpecCollection sourceCollection =
                fromDataClient.getResolvedTiles(parameters.fromStack, z);

        if (parameters.minX != null) {
            final Set<String> tileIdsToKeep = getIdsForTilesInBox(z);
            sourceCollection.filterSpecs(tileIdsToKeep);
        }

        sourceCollection.removeUnreferencedTransforms();

        if (! parameters.keepExisting) {
            toDataClient.deleteStack(parameters.toStack, z);
        }

        toDataClient.saveResolvedTiles(sourceCollection, parameters.toStack, z);
    }

    private Set<String> getIdsForTilesInBox(final Double z) throws Exception {

        final List<TileBounds> tileBoundsList = fromDataClient.getTileBounds(parameters.fromStack, z);
        final TileBoundsRTree tree = new TileBoundsRTree(z, tileBoundsList);

        final Set<String> tileIdsToKeep = new HashSet<>(tileBoundsList.size());

        for (final TileBounds tileBounds : tree.findTilesInBox(parameters.minX, parameters.minY,
                                                               parameters.maxX, parameters.maxY)) {
            tileIdsToKeep.add(tileBounds.getTileId());
        }

        if (tileBoundsList.size() > tileIdsToKeep.size()) {
            LOG.info("getIdsForTilesInBox: removed {} tiles outside of bounding box",
                     (tileBoundsList.size() - tileIdsToKeep.size()));
        }

        return tileIdsToKeep;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyStackClient.class);
}
