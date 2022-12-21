package org.janelia.render.client;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.PRE_CONCATENATE_LAST;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.models.Affine2D;
import mpicbg.trakem2.transform.AffineModel2D;

/**
 * This client currently is a one-off hack that could be improved later to make it more generally useful.
 *
 * It was written to support merging 2 aligned stacks where the first stack had 9500 layers and second stack
 * extended those by another 7000 layers.  The second stack was completely realigned, but later we learned
 * we needed to preserve the transformations from the first stack.  This tool allowed us to
 * calculate a new transformation (output in the logs) necessary to move the "newer" 7000 layers from the
 * second stack into the same space as the first stack.
 *
 * The tool creates 3 additional stacks (merge_base_[runtime], merge_base_[runtime]_align, and merge_check_[runtime])
 * and one match collection ([project]_merge_alignments) to support the derivation
 * and to confirm it works (the merge_check_[runtime] stack).
 *
 * It assumes that the layers are single tile, that they can be aligned easily with cross pass 1 parameters,
 * and that there is at least one layer before and after the transition.  If we ever have occasion to
 * need this tool again, we can spend more time to reduce such assumptions then.
 *
 * @author Eric Trautman
 */
public class HackMergeTransformClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--firstAlignedStack",
                description = "Name of first aligned stack that needs to remain as is",
                required = true)
        public String firstAlignedStack;

        @Parameter(
                names = "--firstStackLastZ",
                description = "Z value of the last layer in the first stack to which the second stack should be aligned",
                required = true)
        public Double firstStackLastZ;

        @Parameter(
                names = "--secondAlignedStack",
                description = "Name of second aligned stack that needs to be translated into the first stack's space",
                required = true)
        public String secondAlignedStack;

        @Parameter(
                names = "--secondStackFirstZ",
                description = "Z value of the first layer in the second stack to align")
        public Double secondStackFirstZ;
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final HackMergeTransformClient client = new HackMergeTransformClient(parameters);

                client.run();
            }
        };

        clientRunner.run();
    }

    private final Parameters parameters;
    final RenderDataClient renderDataClient;

    public HackMergeTransformClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    public void run() throws Exception {

        final String timeStamp = new SimpleDateFormat("_yyyyMMdd_HHmmss").format(new Date());
        final String mergedBaseStack = "merged_base" + timeStamp;

        final String versionNotes = "merge " + parameters.firstAlignedStack + " z " + parameters.firstStackLastZ +
                                    " with " + parameters.secondAlignedStack + " z " + parameters.secondStackFirstZ;

        final StackMetaData firstStackMetaData = renderDataClient.getStackMetaData(parameters.firstAlignedStack);
        final List<Double> resolutionValues = firstStackMetaData.getCurrentResolutionValues();

        final StackVersion stackVersion =
                new StackVersion(new Date(),
                                 versionNotes,
                                 null,
                                 null,
                                 resolutionValues.size() == 3 ? resolutionValues.get(0) : null,
                                 resolutionValues.size() == 3 ? resolutionValues.get(1) : null,
                                 resolutionValues.size() == 3 ? resolutionValues.get(2) : null,
                                 null,
                                 firstStackMetaData.getCurrentMipmapPathBuilder());

        renderDataClient.saveStackVersion(mergedBaseStack, stackVersion);

        final ResolvedTileSpecCollection resolvedTiles = new ResolvedTileSpecCollection();

        final String pTileId = "p_layer_" + parameters.firstStackLastZ;
        addLayerTileSpecToCollection(resolvedTiles, pTileId);

        final TileSpec secondTileSpec = getTileSpec(parameters.secondAlignedStack, parameters.secondStackFirstZ);
        final String qTileId = "q_" + secondTileSpec.getTileId();
        secondTileSpec.setTileId(qTileId);
        resolvedTiles.addTileSpecToCollection(secondTileSpec);

        renderDataClient.saveResolvedTiles(resolvedTiles, mergedBaseStack, null);

        renderDataClient.setStackState(mergedBaseStack, StackMetaData.StackState.COMPLETE);

        final String mergeMatchCollection = parameters.renderWeb.project + "_merge_alignments";
        final RenderDataClient matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                      parameters.renderWeb.owner,
                                                                      mergeMatchCollection);
        for (final MatchCollectionMetaData mcmd : matchDataClient.getOwnerMatchCollections()) {
            if (mergeMatchCollection.equals(mcmd.getCollectionId().getName())) {
                matchDataClient.deleteMatchCollection(mergeMatchCollection);
                break;
            }
        }

        final int matchedPairCount =
                deriveAndSaveMatches(mergedBaseStack, mergeMatchCollection, pTileId, qTileId, 1.0);

        if (matchedPairCount != 1) {
            throw new IllegalStateException(
                    "match derivation should have produced 1 matched pair but instead produced " +
                    matchedPairCount + " matched pairs");
        }

        final String targetStack = mergedBaseStack + "_align";

        final String[] solverArgs = {
                "--baseDataUrl", parameters.renderWeb.baseDataUrl,
                "--owner", parameters.renderWeb.owner,
                "--project", parameters.renderWeb.project,
                "--stack", mergedBaseStack,
                "--targetStack", targetStack,
                "--minZ", String.valueOf(parameters.firstStackLastZ),
                "--maxZ", String.valueOf(parameters.secondStackFirstZ),
                "--completeTargetStack",
                "--matchCollection", mergeMatchCollection,
                "--regularizerModelType", "RIGID",
                "--fixedTileIds", pTileId
        };

        final Trakem2SolverClient.Parameters solverParameters = new Trakem2SolverClient.Parameters();
        solverParameters.parse(solverArgs);
        final Trakem2SolverClient<?> solverClient = new Trakem2SolverClient<>(solverParameters);
        solverClient.run();

        // ----------------------------------------------------------
        // from Herr Saalfeld ... derive A*B-inverse transform where:
        //   A aligns secondStackFirstZ to firstStackLastZ and
        //   B is the original secondStackFirstZ transform

        final TileSpec baseMergeTileSpec = getTileSpec(targetStack,
                                                       parameters.secondStackFirstZ);
        final TransformSpec baseMergeTransformSpec = baseMergeTileSpec.getLastTransform();
        LOG.info("base merge transform spec A is: {}", baseMergeTransformSpec.toJson());
        final AffineModel2D affineA = getAffineModelForSpec("A", baseMergeTransformSpec);

        final TransformSpec secondTransformSpec = secondTileSpec.getLastTransform();
        LOG.info("second transform spec B is: {}", secondTransformSpec.toJson());

        final AffineModel2D affineB = getAffineModelForSpec("B", secondTransformSpec);
        final AffineModel2D affineAAndBInverse = affineB.createInverse();
        affineAAndBInverse.preConcatenate(affineA);

        final TransformSpec mergeTransformSpec =
                new LeafTransformSpec(AffineModel2D.class.getName(), affineAAndBInverse.toDataString());
        LOG.info("merge transform spec A*B-inverse is: {}", mergeTransformSpec.toJson());

        final String mergeCheckStack = "merged_check" + timeStamp;
        renderDataClient.saveStackVersion(mergeCheckStack, stackVersion);

        final ResolvedTileSpecCollection checkResolvedTiles = new ResolvedTileSpecCollection();
        final TileSpec firstMinusOneTileSpec = getTileSpec(parameters.firstAlignedStack,
                                                           parameters.firstStackLastZ - 1.0);
        final TileSpec firstTileSpec = getTileSpec(parameters.firstAlignedStack,
                                                   parameters.firstStackLastZ);
        checkResolvedTiles.addTileSpecToCollection(firstMinusOneTileSpec);
        checkResolvedTiles.addTileSpecToCollection(firstTileSpec);

        final TileSpec secondPlusOneTileSpec = getTileSpec(parameters.secondAlignedStack,
                                                           parameters.secondStackFirstZ + 1.0);
        checkResolvedTiles.addTileSpecToCollection(secondTileSpec);
        checkResolvedTiles.addTransformSpecToTile(
                secondTileSpec.getTileId(),
                mergeTransformSpec,
                PRE_CONCATENATE_LAST);
        checkResolvedTiles.addTileSpecToCollection(secondPlusOneTileSpec);
        checkResolvedTiles.addTransformSpecToTile(
                secondPlusOneTileSpec.getTileId(),
                mergeTransformSpec,
                PRE_CONCATENATE_LAST);

        renderDataClient.saveResolvedTiles(checkResolvedTiles, mergeCheckStack, null);

        renderDataClient.setStackState(mergeCheckStack, StackMetaData.StackState.COMPLETE);
    }

    public TileSpec getTileSpec(final String stack,
                                final double z)
            throws IOException {
        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);
        return resolvedTiles.getTileSpecs().stream().findFirst()
                .orElseThrow(() -> new IOException("no tiles in stack " + stack + " for z " + z));
    }

    public static AffineModel2D getAffineModelForSpec(final String context,
                                                      final TransformSpec transformSpec) {
        final mpicbg.models.CoordinateTransform transform = transformSpec.getNewInstance();
        final AffineModel2D affineModel;
        if (transform instanceof AffineModel2D) {
            affineModel = (AffineModel2D) transform;
        }  else if (transform instanceof Affine2D) {
            affineModel = new AffineModel2D();
            affineModel.set(((Affine2D<?>) transform).createAffine());
        } else {
            throw new IllegalArgumentException(context + " transform must implement " + Affine2D.class.getName());
        }
        return affineModel;
    }

    public void addLayerTileSpecToCollection(final ResolvedTileSpecCollection resolvedTiles,
                                             final String tileId)
            throws IOException {

        final String stack = parameters.firstAlignedStack;
        final Double z = parameters.firstStackLastZ;
        final SectionData sectionData = renderDataClient.getStackSectionData(stack, z, z).get(0);
        final Double sectionMinX = sectionData.getMinX();
        final Double sectionMinY = sectionData.getMinY();

        final TileSpec tileSpec = new TileSpec();

        tileSpec.setTileId(tileId);
        tileSpec.setLayout(new LayoutData(String.valueOf(z), "n/a", "n/a",
                                          0, 0, sectionMinX, sectionMinY, 0.0));
        tileSpec.setZ(z);
        tileSpec.setWidth((double) sectionData.getWidth());
        tileSpec.setHeight((double) sectionData.getHeight());

        final ChannelSpec channelSpec = new ChannelSpec();

        // need hacky .png suffix on URL to get it to work with default imagej openers
        final String boxUrl = parameters.renderWeb.baseDataUrl + "/owner/" + parameters.renderWeb.owner +
                              "/project/" + parameters.renderWeb.project + "/stack/" + stack +
                              "/z/" + z + "/png-image?scale=1.0&imagejsuffix=.png";
        channelSpec.putMipmap(0, new ImageAndMask(boxUrl, null));

        tileSpec.addChannel(channelSpec);

        final TransformSpecMetaData transformSpecMetaData = new TransformSpecMetaData();
        transformSpecMetaData.addLabel("regular");

        // NOTE: important to translate section to same location as in original stack
        final List<TransformSpec> transformSpecList = Collections.singletonList(
                new LeafTransformSpec(null,
                                      null,
                                      AffineModel2D.class.getName(),
                                      "1 0 0 1 " + sectionMinX + " " + sectionMinY));
        tileSpec.addTransformSpecs(transformSpecList);
        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

        resolvedTiles.addTileSpecToCollection(tileSpec);

    }

    public int deriveAndSaveMatches(final String stack,
                                    final String matchCollection,
                                    final String pId,
                                    final String qId,
                                    final double deltaZ)
            throws IOException {

        final SIFTPointMatchClient.Parameters matchParameters =
                getCrossPass1Parameters(parameters.renderWeb.baseDataUrl,
                                        parameters.renderWeb.owner,
                                        matchCollection);
        final SIFTPointMatchClient siftPointMatchClient = new SIFTPointMatchClient(matchParameters);

        final String renderUrlTemplate = "{baseDataUrl}/owner/" + parameters.renderWeb.owner +
                                         "/project/" + parameters.renderWeb.project + "/stack/" + stack +
                                         "/tile/{id}/render-parameters";

        final String pGroupId = String.valueOf(parameters.firstStackLastZ);
        final String qGroupId = String.valueOf(parameters.secondStackFirstZ);
        final List<OrderedCanvasIdPair> neighborPairs = Collections.singletonList(
                new OrderedCanvasIdPair(new CanvasId(pGroupId, pId), new CanvasId(qGroupId, qId), deltaZ)
        );
        final RenderableCanvasIdPairs renderableCanvasIdPairs = new RenderableCanvasIdPairs(renderUrlTemplate,
                                                                                            neighborPairs);
        final List<CanvasMatches> nonEmptyMatchesList =
                siftPointMatchClient.generateMatchesForPairs(renderableCanvasIdPairs,
                                                             matchParameters.matchClient.baseDataUrl,
                                                             matchParameters.featureRender,
                                                             matchParameters.featureRenderClip,
                                                             matchParameters.featureExtraction,
                                                             matchParameters.featureStorage,
                                                             matchParameters.matchDerivation);
        return nonEmptyMatchesList.size();
    }

    public static SIFTPointMatchClient.Parameters getCrossPass1Parameters(final String baseDataUrl,
                                                                          final String owner,
                                                                          final String matchCollection) {
        final String json =
                "{\n" +
                "  \"matchClient\" : {\n" +
                "    \"baseDataUrl\" : \"" + baseDataUrl + "\",\n" +
                "    \"owner\" : \"" + owner + "\",\n" +
                "    \"collection\" : \"" + matchCollection + "\"\n" +
                "  },\n" +
                "  \"featureRender\" : {\n" +
                "    \"renderScale\" : 0.05,\n" +
                "    \"renderWithFilter\" : true,\n" +
                "    \"renderWithoutMask\" : false\n" +
                "  },\n" +
                "  \"featureRenderClip\" : { },\n" +
                "  \"featureExtraction\" : {\n" +
                "    \"fdSize\" : 4,\n" +
                "    \"minScale\" : 0.125,\n" +
                "    \"maxScale\" : 1.0,\n" +
                "    \"steps\" : 5\n" +
                "  },\n" +
                "  \"featureStorage\" : {\n" +
                "    \"requireStoredFeatures\" : false,\n" +
                "    \"maxFeatureCacheGb\" : 1,\n" +
                "    \"maxFeatureSourceCacheGb\" : 1\n" +
                "  },\n" +
                "  \"matchDerivation\" : {\n" +
                "    \"matchRod\" : 0.92,\n" +
                "    \"matchModelType\" : \"RIGID\",\n" +
                "    \"matchIterations\" : 1000,\n" +
                "    \"matchMaxEpsilon\" : 3.0,\n" +
                "    \"matchMinInlierRatio\" : 0.0,\n" +
                "    \"matchMinNumInliers\" : 20,\n" +
                "    \"matchMaxTrust\" : 4.0,\n" +
                "    \"matchFilter\" : \"SINGLE_SET\",\n" +
                "    \"matchFullScaleCoverageRadius\" : 300.0\n" +
                "  },\n" +
                "  \"geometricDescriptorAndMatch\" : {\n" +
                "    \"gdEnabled\" : false\n" +
                "    },\n" +
                "    \"minCombinedInliers\" : 20,\n" +
                "    \"minCombinedCoveragePercentage\" : 55.0\n" +
                "  },\n" +
                "  \"pairJson\" : [\n" +
                "    \"/not_applicable.json\"\n" +
                "  ]\n" +
                "}";

        final JsonUtils.Helper<SIFTPointMatchClient.Parameters> helper =
                new JsonUtils.Helper<>(SIFTPointMatchClient.Parameters.class);

        return helper.fromJson(json);
    }

    private static final Logger LOG = LoggerFactory.getLogger(HackMergeTransformClient.class);

}
