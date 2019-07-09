package org.janelia.render.client.spark;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;

import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.TierZeroStack;
import org.janelia.render.client.RenderDataClient;

/**
 * Spark function that applies montage scape alignment results for a layer
 * to the corresponding layer in a rough tiles stack.
 *
 * @author Eric Trautman
 */
public class HierarchicalRoughStackFunction
        implements Function<Double, Integer> {

    private final String baseDataUrl;
    private final TierZeroStack tierZeroStack;
    private final StackId roughStackId;

    HierarchicalRoughStackFunction(final String baseDataUrl,
                                   final TierZeroStack tierZeroStack,
                                   final StackId roughStackId) {
        this.baseDataUrl = baseDataUrl;
        this.tierZeroStack = tierZeroStack;
        this.roughStackId = roughStackId;
    }

    @Override
    public Integer call(final Double z)
            throws Exception {

        LogUtilities.setupExecutorLog4j(z.toString());

        final StackId montageStackId = tierZeroStack.getParentTierStackId();

        final RenderDataClient montageDataClient =
                new RenderDataClient(baseDataUrl,
                                     montageStackId.getOwner(),
                                     montageStackId.getProject());

        final Bounds montageLayerBounds = montageDataClient.getLayerBounds(montageStackId.getStack(), z);

        final StackId alignedStackId = tierZeroStack.getAlignedStackId();

        final RenderDataClient alignedDataClient =
                new RenderDataClient(baseDataUrl,
                                     alignedStackId.getOwner(),
                                     alignedStackId.getProject());

        final ResolvedTileSpecCollection tileSpecCollection =
                alignedDataClient.getResolvedTiles(alignedStackId.getStack(), z);

        if (tileSpecCollection.getTileCount() != 1) {
            throw new IllegalArgumentException("layer " + z + " in " + alignedStackId + " has " +
                                               tileSpecCollection.getTileCount() + " tile specs (should only be one)");
        }

        final TileSpec layerTileSpec = tileSpecCollection.getTileSpecs().iterator().next();

        final LeafTransformSpec roughAlignmentTransform;

        final TransformSpec lastTransform = layerTileSpec.getLastTransform();
        final CoordinateTransform transformInstance = lastTransform.getNewInstance();

        if (transformInstance instanceof AffineModel2D) {
            final AffineModel2D scaledAffineModel = (AffineModel2D) transformInstance;
            final AffineModel2D fullScaleRoughModel =
                    HierarchicalStack.getFullScaleRelativeModel(scaledAffineModel,
                                                                tierZeroStack.getScale(),
                                                                montageLayerBounds);
            final mpicbg.trakem2.transform.AffineModel2D specModel = new mpicbg.trakem2.transform.AffineModel2D();
            specModel.set(fullScaleRoughModel);

            final String roughTransformId = "z_" + z + "_ROUGH_ALIGN";
            roughAlignmentTransform = new LeafTransformSpec(roughTransformId,
                                                            null,
                                                            specModel.getClass().getName(),
                                                            specModel.toDataString());
        } else {
            throw new IllegalArgumentException("last transform for tileId '" + layerTileSpec.getTileId() +
                                               "' is a " + transformInstance.getClass().getName() + " instance");
        }

        final ResolvedTileSpecCollection tileCollection =
                montageDataClient.getResolvedTiles(montageStackId.getStack(), z);

        // tileCollection.addTransformSpecToCollection(roughAlignmentTransform);
        // tileCollection.addReferenceTransformToAllTiles(roughAlignmentTransform.getId(), false);
        tileCollection.preConcatenateTransformToAllTiles(roughAlignmentTransform);

        final RenderDataClient roughDataClient =
                new RenderDataClient(baseDataUrl,
                                     roughStackId.getOwner(),
                                     roughStackId.getProject());
        roughDataClient.saveResolvedTiles(tileCollection, roughStackId.getStack(), z);

        return tileCollection.getTileCount();
    }
}
