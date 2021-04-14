package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.*;

/**
 * Java client for adding a transform to all tiles in one or more sections of a stack.
 *
 * @author Eric Trautman
 */
public class TransformSectionClient {

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
                names = "--targetProject",
                description = "Name of target project that will contain transformed tiles (default is to reuse source project)"
        )
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack that will contain transformed tiles (default is to reuse source stack)"
        )
        public String targetStack;

        @Parameter(
                names = "--transformId",
                description = "Identifier for transformation",
                required = true)
        public String transformId;

        @Parameter(
                names = "--transformClass",
                description = "Name of transformation implementation (java) class",
                required = true)
        public String transformClass;

        // TODO: figure out less hacky way to handle spaces in transform data string
        @Parameter(
                names = "--transformData",
                description = "Data with which transformation implementation should be initialized (expects values to be separated by ',' instead of ' ')",
                required = true)
        public String transformData;

        @Parameter(
                names = "--transformApplicationMethod",
                description = "Identifies how this transform should be applied to each tile")
        public ResolvedTileSpecCollection.TransformApplicationMethod transformApplicationMethod = APPEND;

        @Parameter(
                names = "--layerMinimumXAndYBound",
                description = "If specified, transformClass and transformData parameters are ignored " +
                              "and tiles in each layer are simply translated so that the layer's " +
                              "minimum X and Y bounds are this value")
        public Integer layerMinimumXAndYBound;

        @Parameter(
                names = "--fromTileId",
                description = "If specified, transformClass and transformData parameters are ignored " +
                              "and all tiles in this tile's layer are simply translated so that this tile's " +
                              "minimum X and Y bounds are the same as the target 'to' tile's minimum X and Y.  " +
                              "Z value(s) are also ignored and the only layer moved will be the from tile's layer.")
        public String fromTileId;

        @Parameter(
                names = "--toTileId",
                description = "See fromTileId parameter description")
        public String toTileId;

        // if specified, only transform tiles within these bounds
        @ParametersDelegate
        public LayerBoundsParameters layerBounds = new LayerBoundsParameters();

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after transforming all layers",
                arity = 0)
        public boolean completeTargetStack = false;

        @Parameter(
                description = "Z values",
                required = true)
        public List<Double> zValues;

        public String getTargetStack() {
            if ((targetStack == null) || (targetStack.trim().length() == 0)) {
                targetStack = stack;
            }
            return targetStack;
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

                final TransformSectionClient client = new TransformSectionClient(parameters);

                client.setupDerivedStack();

                for (final Double z : client.applicableZValues) {
                    client.transformTilesForZ(z);
                }

                if (parameters.completeTargetStack) {
                    client.completeTargetStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;
    private final LeafTransformSpec stackTransform;

    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;
    private final Map<Double, Bounds> zToBoundsMap;
    private final List<Double> applicableZValues;

    private TransformSectionClient(final Parameters parameters)
            throws IOException {

        this.parameters = parameters;

        this.sourceRenderDataClient = parameters.renderWeb.getDataClient();

        if ((parameters.fromTileId != null) && (parameters.toTileId != null)) {

            final TileSpec fromTileSpec = sourceRenderDataClient.getTile(parameters.stack, parameters.fromTileId);
            final Double fromTileZ = fromTileSpec.getZ();
            final TileSpec toTileSpec = sourceRenderDataClient.getTile(parameters.stack, parameters.toTileId);
            final Double toTileZ = toTileSpec.getZ();

            if (fromTileZ.equals(toTileZ)) {
                throw new IllegalArgumentException(
                        "Both source and target tiles cannot be in the same layer (" + toTileZ + ").");
            }

            final TranslationModel2D model = new TranslationModel2D();
            model.set(toTileSpec.getMinX() - fromTileSpec.getMinX(),
                      toTileSpec.getMinY() - fromTileSpec.getMinY());

            this.stackTransform = new LeafTransformSpec("MOVE_" + fromTileZ + "_TO_" + toTileZ,
                                                        null,
                                                        model.getClass().getName(),
                                                        model.toDataString());
            this.applicableZValues = Collections.singletonList(fromTileZ);

        } else {

            this.stackTransform = new LeafTransformSpec(parameters.transformId,
                                                        null,
                                                        parameters.transformClass,
                                                        parameters.transformData.replace(',', ' '));
            this.applicableZValues = parameters.zValues;
        }

        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();

        if ((parameters.targetProject == null) ||
            (parameters.targetProject.trim().length() == 0) ||
            (parameters.targetProject.equals(parameters.renderWeb.project))) {
            this.targetRenderDataClient = sourceRenderDataClient;
        } else {
            this.targetRenderDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                               parameters.renderWeb.owner,
                                                               parameters.targetProject);
        }

        this.zToBoundsMap = new HashMap<>();

        if (parameters.layerMinimumXAndYBound != null) {

            // load section bounds before setting up derived stack in case source and target stacks are the same

            final List<SectionData> sectionDataList = sourceRenderDataClient.getStackSectionData(parameters.stack,
                                                                                                 null,
                                                                                                 null,
                                                                                                 parameters.zValues);
            sectionDataList.forEach(sd -> {
                final Bounds bounds = this.zToBoundsMap.get(sd.getZ());
                if (bounds == null) {
                    this.zToBoundsMap.put(sd.getZ(), sd.toBounds());
                } else {
                    this.zToBoundsMap.put(sd.getZ(), bounds.union(sd.toBounds()));
                }
            });
        }
    }

    private void setupDerivedStack()
            throws IOException {
        final StackMetaData sourceStackMetaData = sourceRenderDataClient.getStackMetaData(parameters.stack);
        targetRenderDataClient.setupDerivedStack(sourceStackMetaData, parameters.getTargetStack());
    }

    private void completeTargetStack() throws Exception {
        targetRenderDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
    }

    private void transformTilesForZ(final Double z)
            throws Exception {

        LOG.info("transformTilesForZ: entry, z={}", z);

        final ResolvedTileSpecCollection tiles = sourceRenderDataClient.getResolvedTiles(parameters.stack, z);

        if (parameters.layerBounds.isDefined()) {
            removeTilesOutsideBox(tiles);
        }

        final LeafTransformSpec layerTransform;
        if (parameters.layerMinimumXAndYBound != null) {

            final String layerTransformId = stackTransform.getId() + "_" + z;
            final AffineModel2D model = new AffineModel2D();

            final Bounds layerBounds = zToBoundsMap.get(z);
            model.set(1, 0, 0, 1,
                      (parameters.layerMinimumXAndYBound - layerBounds.getMinX()),
                      (parameters.layerMinimumXAndYBound - layerBounds.getMinY()));

            layerTransform = new LeafTransformSpec(layerTransformId,
                                                   null,
                                                   model.getClass().getName(),
                                                   model.toDataString());

        } else {
            layerTransform = stackTransform;
        }

        if (PRE_CONCATENATE_LAST.equals(parameters.transformApplicationMethod)) {
            tiles.preConcatenateTransformToAllTiles(layerTransform);
        } else {
            tiles.addTransformSpecToCollection(layerTransform);
            tiles.addReferenceTransformToAllTiles(layerTransform.getId(),
                                                  REPLACE_LAST.equals(parameters.transformApplicationMethod));
        }

        final int totalNumberOfTiles = tiles.getTileCount();
        if (tileSpecValidator != null) {
            tiles.setTileSpecValidator(tileSpecValidator);
            tiles.removeInvalidTileSpecs();
        }
        final int numberOfRemovedTiles = totalNumberOfTiles - tiles.getTileCount();

        if (tiles.getTileCount() > 0) {
            LOG.info("transformTilesForZ: added transform and derived bounding boxes for {} tiles with z of {}, removed {} bad tiles",
                     totalNumberOfTiles, z, numberOfRemovedTiles);

            targetRenderDataClient.saveResolvedTiles(tiles, parameters.getTargetStack(), z);
        } else {
            LOG.info("transformTilesForZ: no tiles left for z {}, skipping save", z);
        }

        LOG.info("transformTilesForZ: exit, saved tiles and transforms for {}", z);
    }

    private void removeTilesOutsideBox(final ResolvedTileSpecCollection tiles) {

        final List<TileBounds> tileBoundsList =
                tiles.getTileSpecs().stream().map(TileSpec::toTileBounds).collect(Collectors.toList());
        final TileBoundsRTree tree = new TileBoundsRTree(null, tileBoundsList);

        final Set<String> tileIdsToKeep = new HashSet<>(tileBoundsList.size());

        tileIdsToKeep.addAll(
                tree.findTilesInBox(parameters.layerBounds.minX,
                                    parameters.layerBounds.minY,
                                    parameters.layerBounds.maxX,
                                    parameters.layerBounds.maxY).stream().map(
                        TileBounds::getTileId).collect(Collectors.toList()));

        if (tileBoundsList.size() > tileIdsToKeep.size()) {
            tiles.removeDifferentTileSpecs(tileIdsToKeep);
            LOG.info("removeTilesOutsideBox: removed {} tiles outside of bounding box",
                     (tileBoundsList.size() - tileIdsToKeep.size()));

        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(TransformSectionClient.class);
}
