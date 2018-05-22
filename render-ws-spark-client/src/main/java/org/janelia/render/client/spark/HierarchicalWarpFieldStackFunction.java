package org.janelia.render.client.spark;

import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.transform.ConsensusWarpFieldBuilder;
import org.janelia.render.client.RenderDataClient;

/**
 * Spark function that calls web service to derive warp field transform for a layer
 * and then append it to the transform list of each tile in the layer.
 *
 * @author Eric Trautman
 */
public class HierarchicalWarpFieldStackFunction
        implements Function<Double, Integer> {

    private final String baseDataUrl;
    private final String owner;
    private final int tier;
    private final String tierProject;
    private final StackId parentTilesStackId;
    private final String warpStackName;
    private final ConsensusWarpFieldBuilder.BuildMethod consensusBuildMethod;

    public HierarchicalWarpFieldStackFunction(final String baseDataUrl,
                                              final String owner,
                                              final int tier,
                                              final String tierProject,
                                              final StackId parentTilesStackId,
                                              final String warpStackName,
                                              final ConsensusWarpFieldBuilder.BuildMethod consensusBuildMethod) {
        this.baseDataUrl = baseDataUrl;
        this.owner = owner;
        this.tier = tier;
        this.tierProject = tierProject;
        this.parentTilesStackId = parentTilesStackId;
        this.warpStackName = warpStackName;
        this.consensusBuildMethod = consensusBuildMethod;
    }

    @Override
    public Integer call(final Double z)
            throws Exception {

        LogUtilities.setupExecutorLog4j(z.toString());

        final RenderDataClient localTierDataClient =
                new RenderDataClient(baseDataUrl,
                                     owner,
                                     tierProject);

        final LeafTransformSpec warpFieldTransform =
                localTierDataClient.getAffineWarpFieldTransform(z, consensusBuildMethod);

        final String tierTransformId = warpFieldTransform.getId() + "_tier_" + tier;
        final LeafTransformSpec tierWarpFieldTransform = new LeafTransformSpec(tierTransformId,
                                                                               null,
                                                                               warpFieldTransform.getClassName(),
                                                                               warpFieldTransform.getDataString());
        final RenderDataClient localTilesDataClient =
                new RenderDataClient(baseDataUrl,
                                     owner,
                                     parentTilesStackId.getProject());

        final ResolvedTileSpecCollection tileCollection =
                localTilesDataClient.getResolvedTiles(parentTilesStackId.getStack(), z);

        tileCollection.addTransformSpecToCollection(tierWarpFieldTransform);
        tileCollection.addReferenceTransformToAllTiles(tierWarpFieldTransform.getId(), false);

        localTilesDataClient.saveResolvedTiles(tileCollection, warpStackName, z);

        return tileCollection.getTileCount();
    }
}
