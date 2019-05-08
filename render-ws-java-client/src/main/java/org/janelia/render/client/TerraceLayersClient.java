package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.*;

/**
 * Java client for "terracing" all layers in a stack so that no layer overlaps with another.
 *
 * @author Eric Trautman
 */
public class TerraceLayersClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

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
                names = "--transformApplicationMethod",
                description = "Identifies how this transform should be applied to each tile")
        public ResolvedTileSpecCollection.TransformApplicationMethod transformApplicationMethod = APPEND;

        @Parameter(
                names = "--minX",
                description = "Minimum X bound for the stack")
        public Integer minX = 10;

        @Parameter(
                names = "--minY",
                description = "Minimum Y bound for the stack")
        public Integer minY = 10;

        @Parameter(
                names = "--margin",
                description = "Pixel distance between each terraced layer")
        public Integer margin = 3000;

        @Parameter(
                names = "--verticalOrientation",
                description = "Terrace layers vertically (default is horizontal terracing)",
                arity = 0)
        public boolean verticalOrientation = false;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after transforming all layers",
                arity = 0)
        public boolean completeTargetStack = false;

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

                LOG.info("runClient: entry, parameters={}", parameters);

                final TerraceLayersClient client = new TerraceLayersClient(parameters);

                client.setupDerivedStack();
                client.terraceLayers();
                if (parameters.completeTargetStack) {
                    client.completeTargetStack();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;
    private final Map<Double, Bounds> zToBoundsMap;

    private TerraceLayersClient(final Parameters parameters)
            throws IOException {

        this.parameters = parameters;

        this.sourceRenderDataClient = parameters.renderWeb.getDataClient();

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

        final List<SectionData> sectionDataList = sourceRenderDataClient.getStackSectionData(parameters.stack,
                                                                                             null,
                                                                                             null,
                                                                                             null);
        sectionDataList.forEach(sd -> {
            final Bounds bounds = this.zToBoundsMap.get(sd.getZ());
            if (bounds == null) {
                this.zToBoundsMap.put(sd.getZ(), sd.toBounds());
            } else {
                this.zToBoundsMap.put(sd.getZ(), bounds.union(sd.toBounds()));
            }
        });
    }

    private void setupDerivedStack()
            throws IOException {
        final StackMetaData sourceStackMetaData = sourceRenderDataClient.getStackMetaData(parameters.stack);
        targetRenderDataClient.setupDerivedStack(sourceStackMetaData, parameters.getTargetStack());
    }

    private void completeTargetStack() throws Exception {
        targetRenderDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
    }

    private void terraceLayers()
            throws IOException {

        LOG.info("terraceLayers: entry");

        final List<Double> sortedZs = zToBoundsMap.keySet().stream().sorted().collect(Collectors.toList());

        double minX = parameters.minX;
        double minY = parameters.minY;

        for (final Double z : sortedZs) {

            final Bounds layerBounds = zToBoundsMap.get(z);
            moveLayer(z, layerBounds, minX, minY);

            if (parameters.verticalOrientation) {
                minY = minY + layerBounds.getDeltaY() + parameters.margin;
            } else {
                minX = minX + layerBounds.getDeltaX() + parameters.margin;
            }

        }

        LOG.info("terraceLayers: exit");
    }

    private void moveLayer(final double z,
                           final Bounds layerBounds,
                           final double toX,
                           final double toY)
            throws IOException {

        final ResolvedTileSpecCollection tiles = sourceRenderDataClient.getResolvedTiles(parameters.stack, z);

        final String layerTransformId = "TERRACE_" + z;
        final AffineModel2D model = new AffineModel2D();

        model.set(1, 0, 0, 1,
                  (toX - layerBounds.getMinX()),
                  (toY - layerBounds.getMinY()));

        final LeafTransformSpec layerTransform = new LeafTransformSpec(layerTransformId,
                                                                       null,
                                                                       model.getClass().getName(),
                                                                       model.toDataString());

        if (PRE_CONCATENATE_LAST.equals(parameters.transformApplicationMethod)) {
            tiles.preConcatenateTransformToAllTiles(layerTransform);
        } else {
            tiles.addTransformSpecToCollection(layerTransform);
            tiles.addReferenceTransformToAllTiles(layerTransform.getId(),
                                                  REPLACE_LAST.equals(parameters.transformApplicationMethod));
        }

        targetRenderDataClient.saveResolvedTiles(tiles, parameters.getTargetStack(), z);

        LOG.info("moveLayer: exit, updated {} tiles for z {}", tiles.getTileCount(), z);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TerraceLayersClient.class);
}
