package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.REPLACE_LAST;

/**
 * Java client for copying tiles from one stack to another.
 *
 * @author Eric Trautman
 */
public class CopyStackClient {

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
                description = "Z value of section to be copied",
                required = true)
        public List<Double> zValues;

        @Parameter(
                names = "--moveToOrigin",
                description = "If necessary, translate copied stack so that it's minX and minY are near the origin (default is to copy exact location)",
                arity = 0)
        public boolean moveToOrigin = false;

        @Parameter(
                names = "--excludeTileIdsMissingFromStacks",
                description = "Name(s) of stack(s) that contain ids of tiles to be included in target stack (assumes owner and project are same as source stack).",
                variableArity = true
        )
        public List<String> excludeTileIdsMissingFromStacks;

        @ParametersDelegate
        LayerBoundsParameters layerBounds = new LayerBoundsParameters();

        @Parameter(
                names = "--keepExisting",
                description = "Keep any existing target stack tiles with the specified z (default is to remove them)",
                arity = 0)
        public boolean keepExisting = false;

        @Parameter(
                names = "--completeToStackAfterCopy",
                description = "Complete the to stack after copying all layers",
                arity = 0)
        public boolean completeToStackAfterCopy = false;

        @Parameter(
                names = "--replaceLastTransformWithStage",
                description = "Replace the last transform in each tile space with a 'stage identity' transform",
                arity = 0)
        public boolean replaceLastTransformWithStage = false;

        @Parameter(
                names = "--splitMergedSections",
                description = "Reset z values for tiles so that original sections are separated",
                arity = 0)
        public boolean splitMergedSections = false;

        @Parameter(
                names = "--maxSectionsPerOriginalZ",
                description = "Maximum number of sections for each layer " +
                              "(for CATMAID it is best if this evenly divides into the source stack zResolution e.g. 40 for FAFB)")
        public Integer maxSectionsPerOriginalZ = 40;

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

                final CopyStackClient client = new CopyStackClient(parameters);

                client.setUpDerivedStack();

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
    private final Map<String, Double> sectionIdToZMap;
    private final LeafTransformSpec moveStackTransform;

    private CopyStackClient(final Parameters parameters) throws Exception {

        this.parameters = parameters;

        this.fromDataClient = parameters.renderWeb.getDataClient();

        this.toDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                 parameters.getToOwner(),
                                                 parameters.getToProject());

        if (parameters.splitMergedSections) {
            if ((parameters.maxSectionsPerOriginalZ == null) || (parameters.maxSectionsPerOriginalZ < 1)) {
                throw new IllegalArgumentException(
                        "--maxSectionsPerOriginalZ must be specified when --splitMergedSections is specified");
            }
            this.sectionIdToZMap = getSectionIdToSplitZMap();
        } else {
            this.sectionIdToZMap = null;
        }

        if (parameters.moveToOrigin) {

            if (parameters.replaceLastTransformWithStage) {
                throw new IllegalArgumentException(
                        "please choose either --moveToOrigin or --replaceLastTransformWithStage but not both");
            }

            final StackMetaData sourceStackMetaData = fromDataClient.getStackMetaData(parameters.fromStack);
            final StackStats sourceStackStats = sourceStackMetaData.getStats();
            final Bounds sourceStackBounds = sourceStackStats.getStackBounds();

            final double padding = 10.0;
            if ((sourceStackBounds.getMinX() < 0) || (sourceStackBounds.getMinX() > padding) ||
                (sourceStackBounds.getMinY() < 0) || (sourceStackBounds.getMinY() > padding)) {

                final double xOffset = padding - sourceStackBounds.getMinX();
                final double yOffset = padding - sourceStackBounds.getMinY();
                final String dataString = "1 0 0 1 " + xOffset + " " + yOffset;

                final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_hhmmss_SSS");
                moveStackTransform = new LeafTransformSpec("MOVE_STACK_" + sdf.format(new Date()),
                                                           null,
                                                           AffineModel2D.class.getName(),
                                                           dataString);
            } else {
                LOG.info("skipping move to origin since source stack is already near the origin");
                moveStackTransform = null;
            }

        } else {
            moveStackTransform = null;
        }

    }

    private void setUpDerivedStack() throws Exception {
        final StackMetaData fromStackMetaData = fromDataClient.getStackMetaData(parameters.fromStack);

        if (parameters.splitMergedSections) {
            // change resolution for split stack copies
            final List<Double> resolutionValues = fromStackMetaData.getCurrentResolutionValues();
            if (resolutionValues.size() == 3) {
                final Double zResolution = resolutionValues.remove(2);
                resolutionValues.add(zResolution / parameters.maxSectionsPerOriginalZ);
                fromStackMetaData.setCurrentResolutionValues(resolutionValues);
            }
        }

        toDataClient.setupDerivedStack(fromStackMetaData, parameters.toStack);
    }

    private void completeToStack() throws Exception {
        toDataClient.setStackState(parameters.toStack, StackState.COMPLETE);
    }

    private void copyLayer(final Double z) throws Exception {

        final ResolvedTileSpecCollection sourceCollection =
                fromDataClient.getResolvedTiles(parameters.fromStack, z);

        if (parameters.layerBounds.minX != null) {
            final Set<String> tileIdsToKeep = getIdsForTilesInBox(z);
            sourceCollection.removeDifferentTileSpecs(tileIdsToKeep);
        }

        if (parameters.replaceLastTransformWithStage) {
            replaceLastTransformWithStage(sourceCollection);
        }

        final Set<Double> toStackZValues = new LinkedHashSet<>();
        if (parameters.splitMergedSections) {
            for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {
                final Double zValue = sectionIdToZMap.get(tileSpec.getLayout().getSectionId());
                toStackZValues.add(zValue);
                tileSpec.setZ(zValue);
            }

            LOG.info("copyLayer: updated z values for {} tiles",
                     sourceCollection.getTileCount());
        } else {
            toStackZValues.add(z);
        }

        final Set<String> tileIdsToKeep = new HashSet<>();
        String filterStack = null;
        if (parameters.excludeTileIdsMissingFromStacks != null) {

            for (final String tileIdStack : parameters.excludeTileIdsMissingFromStacks) {

                tileIdsToKeep.addAll(
                        toDataClient.getTileBounds(tileIdStack, z)
                                .stream()
                                .map(TileBounds::getTileId)
                                .collect(Collectors.toList()));

                // once a stack with tiles for the current z is found, use that as the filter
                if (tileIdsToKeep.size() > 0) {
                    filterStack = tileIdStack;
                    break;
                }
            }

        }

        if (tileIdsToKeep.size() > 0) {
            final int numberOfTilesBeforeFilter = sourceCollection.getTileCount();
            sourceCollection.removeDifferentTileSpecs(tileIdsToKeep);
            final int numberOfTilesRemoved = numberOfTilesBeforeFilter - sourceCollection.getTileCount();
            LOG.info("copyLayer: removed {} tiles not found in {}", numberOfTilesRemoved, filterStack);
        }

        if (moveStackTransform != null) {
            sourceCollection.addTransformSpecToCollection(moveStackTransform);
            sourceCollection.addReferenceTransformToAllTiles(moveStackTransform.getId(), false);
        }

        sourceCollection.removeUnreferencedTransforms();

        if (! parameters.keepExisting) {
            for (final Double zValue : toStackZValues) {
                toDataClient.deleteStack(parameters.toStack, zValue);
            }
        }

        toDataClient.saveResolvedTiles(sourceCollection, parameters.toStack, null);
    }

    private Set<String> getIdsForTilesInBox(final Double z) throws Exception {

        final List<TileBounds> tileBoundsList = fromDataClient.getTileBounds(parameters.fromStack, z);
        final TileBoundsRTree tree = new TileBoundsRTree(z, tileBoundsList);

        final Set<String> tileIdsToKeep = new HashSet<>(tileBoundsList.size());

        tileIdsToKeep.addAll(
                tree.findTilesInBox(parameters.layerBounds.minX,
                                    parameters.layerBounds.minY,
                                    parameters.layerBounds.maxX,
                                    parameters.layerBounds.maxY).stream().map(
                        TileBounds::getTileId).collect(Collectors.toList()));

        if (tileBoundsList.size() > tileIdsToKeep.size()) {
            LOG.info("getIdsForTilesInBox: removed {} tiles outside of bounding box",
                     (tileBoundsList.size() - tileIdsToKeep.size()));
        }

        return tileIdsToKeep;
    }

    private void replaceLastTransformWithStage(final ResolvedTileSpecCollection sourceCollection) {

        final ProcessTimer timer = new ProcessTimer();

        int tileSpecCount = 0;

        for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {

            final LayoutData layoutData = tileSpec.getLayout();
            final String dataString = "1 0 0 1 " + layoutData.getStageX() + " " + layoutData.getStageY();
            final TransformSpec transformSpec = new LeafTransformSpec(AffineModel2D.class.getName(),
                                                                      dataString);

            sourceCollection.addTransformSpecToTile(tileSpec.getTileId(),
                                                    transformSpec,
                                                    REPLACE_LAST);

            tileSpecCount++;

            if (timer.hasIntervalPassed()) {
                LOG.info("replaceLastTransformWithStage: updated transforms for {} out of {} tiles",
                         tileSpecCount, sourceCollection.getTileCount());
            }
        }

        LOG.info("replaceLastTransformWithStage: exit, updated transforms for {} tiles",
                 tileSpecCount);
    }

    private Map<String, Double> getSectionIdToSplitZMap()
            throws IOException {

        final Comparator<SectionData> sectionComparator =
                Comparator.comparingDouble(SectionData::getZ).thenComparing(SectionData::getSectionId);

        final List<SectionData> orderedSectionDataList =
                fromDataClient.getStackSectionData(parameters.fromStack, null, null, parameters.zValues);

        orderedSectionDataList.sort(sectionComparator);

        final Map<String, Double> sectionIdToZMap = new HashMap<>(orderedSectionDataList.size());

        if (orderedSectionDataList.size() > 0) {

            Double currentZ = null;
            int sectionIndex = 0;
            for (final SectionData sectionData : orderedSectionDataList) {

                final Double sectionZ = sectionData.getZ();
                if (sectionZ.equals(currentZ)) {
                    sectionIndex++;
                } else {
                    currentZ = sectionZ;
                    sectionIndex = 0;
                }

                if (sectionIndex > parameters.maxSectionsPerOriginalZ) {
                    final Double problemZ = currentZ;
                    final long problemCount =
                            orderedSectionDataList.stream().filter(sd -> problemZ.equals(sd.getZ())).count();
                    throw new IllegalArgumentException(problemCount + " sections exist for z " + currentZ +
                                                       "but --maxSectionsPerOriginalZ is " +
                                                       parameters.maxSectionsPerOriginalZ);
                }

                sectionIdToZMap.put(sectionData.getSectionId(),
                                    ((currentZ * parameters.maxSectionsPerOriginalZ) + sectionIndex));
            }

        }

        LOG.info("getSectionIdToSplitZMap: exit, mapped split z values for {} sections",
                 sectionIdToZMap.size());

        return sectionIdToZMap;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyStackClient.class);
}
