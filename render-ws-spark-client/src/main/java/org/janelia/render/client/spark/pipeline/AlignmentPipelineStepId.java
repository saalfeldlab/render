package org.janelia.render.client.spark.pipeline;

import java.util.function.Supplier;

import org.janelia.render.client.spark.MipmapClient;
import org.janelia.render.client.spark.ScapeClient;
import org.janelia.render.client.spark.tile.MaskHackClient;
import org.janelia.render.client.spark.match.ClusterCountClient;
import org.janelia.render.client.spark.match.CopyMatchClient;
import org.janelia.render.client.spark.match.MultiStagePointMatchClient;
import org.janelia.render.client.spark.multisem.BeamCorrectionSparkClient;
import org.janelia.render.client.spark.multisem.CreepCorrectionSparkClient;
import org.janelia.render.client.spark.multisem.LayerAsTileClient;
import org.janelia.render.client.spark.multisem.MFOVAsTileClient;
import org.janelia.render.client.spark.multisem.MFOVMontageMatchPatchClient;
import org.janelia.render.client.spark.multisem.UnconnectedCrossMFOVClient;
import org.janelia.render.client.spark.newsolver.DistributedAffineBlockSolverClient;
import org.janelia.render.client.spark.newsolver.DistributedIntensityCorrectionBlockSolverClient;
import org.janelia.render.client.spark.tile.RenderTilesClient;
import org.janelia.render.client.spark.tile.TileIdHackClient;
import org.janelia.render.client.spark.zspacing.ZPositionCorrectionClient;

/**
 * Identifier for a step in a spark alignment pipeline with a convenience {@link #toStepClient()} builder.
 */
public enum AlignmentPipelineStepId {

    GENERATE_MIPMAPS(MipmapClient::new),
    DERIVE_TILE_MATCHES(MultiStagePointMatchClient::new),
    PATCH_MFOV_MONTAGE_MATCHES(MFOVMontageMatchPatchClient::new),
    FIND_UNCONNECTED_CROSS_MFOVS(UnconnectedCrossMFOVClient::new),
    FIND_UNCONNECTED_TILES_AND_EDGES(ClusterCountClient::new),
    FILTER_MATCHES(CopyMatchClient::new),
    CORRECT_BEAM_INTENSITY(BeamCorrectionSparkClient::new),
    CORRECT_CREEP(CreepCorrectionSparkClient::new),
    ALIGN_TILES(DistributedAffineBlockSolverClient::new),
    CORRECT_Z_POSITIONS(ZPositionCorrectionClient::new),
    CORRECT_INTENSITY(DistributedIntensityCorrectionBlockSolverClient::new),
    HACK_MASK(MaskHackClient::new),
    HACK_TILE_ID(TileIdHackClient::new),
    RENDER_SCAPE_IMAGES(ScapeClient::new),
    RENDER_TILES(RenderTilesClient::new),
    MFOV_AS_TILE(MFOVAsTileClient::new),
    LAYER_AS_TILE(LayerAsTileClient::new);

    private final Supplier<AlignmentPipelineStep> stepClientSupplier;

    AlignmentPipelineStepId(final Supplier<AlignmentPipelineStep> stepClientSupplier) {
        this.stepClientSupplier = stepClientSupplier;
    }

    public AlignmentPipelineStep toStepClient() {
        return stepClientSupplier.get();
    }
}
