package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

        @Parameter(
                names = "--targetOwner",
                description = "Name of target stack owner (default is same as source stack owner)"
        )
        public String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)"
        )
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack")
        public String targetStack;

        @Parameter(
                names = "--sectionId",
                description = "Section ID(s) for all tiles that should be moved to the new Z",
                variableArity = true)
        public List<String> sectionIdList;

        @Parameter(
                names = "--fromZ",
                description = "Z value(s) for all tiles that should be moved to the new Z",
                variableArity = true)
        public List<Double> fromZList;

        @Parameter(
                names = "--flattenAllLayers",
                description = "Indicates that all layers in the stack should have the same Z",
                arity = 0)
        public boolean flattenAllLayers;

        @Parameter(
                names = "--flattenByDivisor",
                description = "Divide each layer's z by this value and assign the integral result as the new z")
        public Integer flattenByDivisor;

        @Parameter(
                names = "--stackResolutionZ",
                description = "Z resolution (in nanometers) for the stack after layers have been flattened"
        )
        public Double stackResolutionZ;

        @Parameter(names = "--z", description = "new Z value for all layers (omit when using --flattenByDivisor)")
        public Double z;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the stack after updating z values",
                arity = 0)
        public boolean completeTargetStack = false;

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

        private void validate()
                throws IllegalArgumentException {
            if ((z == null) && (flattenByDivisor == null)) {
                throw new IllegalArgumentException("must specify either --z or --flattenByDivisor");
            }
        }

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
                parameters.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final SectionUpdateClient client = new SectionUpdateClient(parameters);
                client.updateSectionsAndLayers();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient targetClient;
    private final String targetStack;
    private final boolean isDerivedStack;
    private final Map<Double, Set<String>> zToSectionIds;

    private SectionUpdateClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();

        if ((parameters.targetOwner != null) ||
            (parameters.targetProject != null)) {

            final String targetOwner =
                    parameters.targetOwner == null ? parameters.renderWeb.owner : parameters.targetOwner;
            final String targetProject =
                    parameters.targetProject == null ? parameters.renderWeb.project : parameters.targetProject;
            this.targetClient = new RenderDataClient(parameters.renderWeb.baseDataUrl, targetOwner, targetProject);

        } else {
            this.targetClient = this.renderDataClient;
        }

        this.targetStack = parameters.targetStack == null ? parameters.stack : parameters.targetStack;

        this.isDerivedStack = ((this.targetClient != this.renderDataClient) ||
                               (! this.targetStack.equals(parameters.stack)));

        this.zToSectionIds = new HashMap<>();
    }

    private void updateSectionsAndLayers()
            throws IOException, IllegalArgumentException {

        final List<SectionData> sectionDataList =
                renderDataClient.getStackSectionData(parameters.stack, null, null);

        final Set<Double> zValues = new HashSet<>();

        if (parameters.flattenByDivisor != null) {

            sectionDataList.forEach(sd -> zValues.add(sd.getZ()));

        } else if (parameters.flattenAllLayers) {

            sectionDataList.stream()
                    .filter(sd -> ! parameters.z.equals(sd.getZ()))
                    .forEach(sd -> zValues.add(sd.getZ()));

        } else if (parameters.fromZList != null) {

            final Set<Double> fromZSet = new HashSet<>(parameters.fromZList);
            sectionDataList.stream()
                    .filter(sd -> (fromZSet.contains(sd.getZ())))
                    .forEach(sd -> zValues.add(sd.getZ()));

        } else if (parameters.sectionIdList != null) {

            final Set<String> sectionIdSet = new HashSet<>(parameters.sectionIdList);
            sectionDataList.stream()
                    .filter(sd -> (sectionIdSet.contains(sd.getSectionId())))
                    .forEach(sd -> zToSectionIds.computeIfAbsent(sd.getZ(),
                                                                 s -> new HashSet<>()).add(sd.getSectionId()));
            zValues.addAll(zToSectionIds.keySet());

        }

        if (zValues.size() == 0) {

            if (parameters.flattenAllLayers) {
                throw new IllegalArgumentException("The stack " + parameters.stack + " is already flattened.");
            } else {
                throw new IllegalArgumentException(
                        "No matching section ids or z values were found in " + parameters.stack +
                        ".  You need to correct your --sectionId or --fromZ parameters.");
            }
        }

        if (isDerivedStack) {
            targetClient.setupDerivedStack(
                    renderDataClient.getStackMetaData(parameters.stack),
                    targetStack);
        } else {
            targetClient.ensureStackIsInLoadingState(parameters.stack, null);
        }

        zValues.stream().sorted().forEach(this::updateZ);

        if ((parameters.stackResolutionZ != null) &&
            (parameters.flattenAllLayers || (parameters.flattenByDivisor != null))) {

            final List<Double> resolutionValues =
                    renderDataClient.getStackMetaData(parameters.stack).getCurrentResolutionValues();

            if ((resolutionValues != null) && (resolutionValues.size() > 2)) {
                resolutionValues.set(2, parameters.stackResolutionZ);
                targetClient.setStackResolutionValues(targetStack, resolutionValues);
            }

        }

        if (parameters.completeTargetStack) {
            targetClient.setStackState(targetStack, StackMetaData.StackState.COMPLETE);
        }

    }

    private void updateZ(final Double z) {

        final String stack = parameters.stack;
        final Set<String> sectionIdSet = zToSectionIds == null ? null : zToSectionIds.get(z);

        final Double updatedZ;
        if (parameters.flattenByDivisor == null) {
            updatedZ = parameters.z;
        } else {
            updatedZ = Math.floor(z / parameters.flattenByDivisor);
        }

        try {

            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);

            if (parameters.isBoxSpecified()) {

                final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, z);
                final Set<String> updateTileIds = new HashSet<>();
                final TileBoundsRTree tree = new TileBoundsRTree(z, tileBoundsList);
                for (final TileBounds tileBounds : tree.findTilesInBox(parameters.minX, parameters.minY,
                                                                       parameters.maxX, parameters.maxY)) {
                    if ((sectionIdSet == null) || sectionIdSet.contains(tileBounds.getSectionId())) {
                        updateTileIds.add(tileBounds.getTileId());
                    }
                }

                if (updateTileIds.size() == 0) {
                    throw new IllegalArgumentException(
                            "no tile specs exist in stack " + stack + " within the bounding box: " +
                            "min (" + parameters.minX + ", " + parameters.minY +
                            "), max (" + parameters.maxX + ", " + parameters.maxY + ")");
                }

                resolvedTiles.removeDifferentTileSpecs(updateTileIds);

            } else if (sectionIdSet != null) {

                final Set<String> updateTileIds =
                        resolvedTiles.getTileSpecs().stream()
                                .filter(ts -> sectionIdSet.contains(ts.getSectionId()))
                                .map(TileSpec::getTileId)
                                .collect(Collectors.toSet());

                if (updateTileIds.size() == 0) {
                    throw new IllegalArgumentException(
                            "no tile specs exist in stack " + stack + " with sectionId(s) " + sectionIdSet);
                }

                resolvedTiles.removeDifferentTileSpecs(updateTileIds);

            }

            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                tileSpec.setZ(updatedZ);
            }

            targetClient.saveResolvedTiles(resolvedTiles, targetStack, null);

        } catch (final IOException ioe) {
            throw new RuntimeException("wrapping exception for stream", ioe);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(SectionUpdateClient.class);
}
