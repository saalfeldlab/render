package org.janelia.render.client.spark.pipeline;

import java.util.function.Supplier;

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

    GENERATE_MIPMAPS(MipmapClient::new),
    DERIVE_TILE_MATCHES(MultiStagePointMatchClient::new),
    PATCH_MFOV_MONTAGE_MATCHES(MFOVMontageMatchPatchClient::new),
    FIND_UNCONNECTED_MFOVS(UnconnectedCrossMFOVClient::new),
    FIND_UNCONNECTED_TILES_AND_EDGES(ClusterCountClient::new),
    FILTER_MATCHES(CopyMatchClient::new),
    ALIGN_TILES(DistributedAffineBlockSolverClient::new);

    private final Supplier<AlignmentPipelineStep> stepClientSupplier;

    AlignmentPipelineStepId(final Supplier<AlignmentPipelineStep> stepClientSupplier) {
        this.stepClientSupplier = stepClientSupplier;
    }

    public AlignmentPipelineStep toStepClient() {
        return stepClientSupplier.get();
    }
}
