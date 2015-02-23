package org.janelia.alignment.warp;

import java.util.Collection;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;

import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptation of Stephan Saalfeld's TrakEM2 deform montages to multi-layer mosaic script
 * ( https://github.com/axtimwalde/fiji-scripts/blob/master/TrakEM2/deform_montages_to_multi_layer_mosaic.bsh )
 * for use in the Render ecosystem.
 *
 * Here are Stephan's original comments about the script:
 *
 * This script takes two related TrakEM2 projects on the same image data as
 * input. The first project has all patches montaged perfectly, the alignment
 * of layers relative to each other is irrelevant. The second project is the
 * result of a multi-layer-mosaic alignment and thus approximates a global
 * alignment of all patches through a rigid transformation per patch.
 *
 * The script warps all layers in the first project such that the center points
 * of all patches are transferred to where they are in the second project.
 * The warp is realized with an MLS transformation per layer with the center
 * points of all tiles being the control points. It is important to understand
 * that the warping has no effect on the smoothness of transitions between
 * patches within the montage.
 *
 * The result is a project with layer montages that are warped such that they
 * resemble the alignment result of the multi-layer-mosaic alignment of the
 * second project. This result is applied to the first project.
 *
 * @author Eric Trautman
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class MovingLeastSquaresBuilder extends AbstractWarpTransformBuilder<MovingLeastSquaresTransform2> {

    /** MLS transform model class (based upon number of point matches) */
    protected final Class<? extends AbstractAffineModel2D<?>> modelClass;
    
    protected Double alpha;

    /**
     * Derives center point matches between the two specified tile lists using tileId to correlate the tiles.
     * The {@link #build} method can then be used to derive a moving least squares transform instance
     * from the point matches.
     *
     * @param  montageTiles  collection of tiles from a montage stack.
     * @param  alignTiles    collection of tiles from an align stack.
     * @param  alpha  transform alpha (optional, default is 1.0)
     */
    public MovingLeastSquaresBuilder(final Collection<TileSpec> montageTiles,
                                     final Collection<TileSpec> alignTiles,
                                     final Double alpha) {
        
        readTileSpecs(montageTiles, alignTiles);
        
        if (w.length == 1) {
            modelClass = TranslationModel2D.class;
        } else if (w.length == 2) {
            modelClass = SimilarityModel2D.class;
        } else {
            modelClass = AffineModel2D.class;
        }

        LOG.info("ctor: exit, derived {} point matches", w.length);
    }

    /**
     * @return an MLS transform instance using this builder's point matches.
     */
    @Override
    public MovingLeastSquaresTransform2 call() throws Exception {

        LOG.info("build: entry");

        final MovingLeastSquaresTransform2 transform = new MovingLeastSquaresTransform2();

        if (alpha != null) {
            transform.setAlpha(alpha.floatValue());
        }
        transform.setModel(modelClass);
        transform.setMatches(p, q, w);

        LOG.info("build: exit");

        return transform;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MovingLeastSquaresBuilder.class);

}
