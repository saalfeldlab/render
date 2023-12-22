package org.janelia.render.client.spark.pipeline;

import org.janelia.render.client.spark.MipmapClient;
import org.janelia.render.client.spark.match.ClusterCountClient;
import org.janelia.render.client.spark.match.CopyMatchClient;
import org.janelia.render.client.spark.match.MultiStagePointMatchClient;
import org.janelia.render.client.spark.multisem.MFOVMontageMatchPatchClient;
import org.janelia.render.client.spark.multisem.UnconnectedCrossMFOVClient;
import org.janelia.render.client.spark.newsolver.DistributedAffineBlockSolverClient;

/**
 * Identifier for a step in a spark alignment pipeline with a convenience {@link #toStepClient()} builder.
 *
 * @author Eric Trautman
 */
public enum AlignmentPipelineStepId {

    GENERATE_MIPMAPS,
    DERIVE_TILE_MATCHES,
    PATCH_MFOV_MONTAGE_MATCHES,
    FIND_UNCONNECTED_MFOVS,
    FIND_UNCONNECTED_TILES_AND_EDGES,
    FILTER_MATCHES,
    ALIGN_TILES;

    public AlignmentPipelineStep toStepClient()
            throws UnsupportedOperationException {
        switch (this) {
            case GENERATE_MIPMAPS:
                return new MipmapClient();
            case DERIVE_TILE_MATCHES:
                return new MultiStagePointMatchClient();
            case PATCH_MFOV_MONTAGE_MATCHES:
                return new MFOVMontageMatchPatchClient();
            case FIND_UNCONNECTED_MFOVS:
                return new UnconnectedCrossMFOVClient();
            case FIND_UNCONNECTED_TILES_AND_EDGES:
                return new ClusterCountClient();
            case FILTER_MATCHES:
                return new CopyMatchClient();
            case ALIGN_TILES:
                return new DistributedAffineBlockSolverClient();
        }
        throw new UnsupportedOperationException("step client not mapped for " + this);
    }
}
