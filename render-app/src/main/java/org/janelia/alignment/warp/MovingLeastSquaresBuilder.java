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
 * {@link AbstractWarpTransformBuilder} implementation that realizes the warp (see {@link #call})
 * with a Moving Least Squares transformation.
 *
 * @author Eric Trautman
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class MovingLeastSquaresBuilder extends AbstractWarpTransformBuilder<MovingLeastSquaresTransform2> {

    /** MLS transform model class (based upon number of point matches) */
    protected final Class<? extends AbstractAffineModel2D<?>> modelClass;

    protected Double alpha;

    final static private float[] toFloats(final double[] doubles) {
        final float[] floats = new float[doubles.length];
        for (int i = 0; i < doubles.length; ++i) {
            floats[i] = (float)doubles[i];
        }
        return floats;
    }

    final static private float[][] toFloats(final double[][] doubles) {
        final float[][] floats = new float[doubles.length][];
        for (int i = 0; i < doubles.length; ++i)
            floats[i] = toFloats(doubles[i]);

        return floats;
    }

    /**
     * Derives center point matches between the two specified tile lists using tileId to correlate the tiles.
     * The {@link #call} method can then be used to derive a moving least squares transform instance
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

        this.alpha = alpha;
    }

    /**
     * @return an MLS transform instance using this builder's point matches.
     */
    @Override
    public MovingLeastSquaresTransform2 call() throws Exception {

        LOG.info("call: entry");

        final MovingLeastSquaresTransform2 transform = new MovingLeastSquaresTransform2();

        if (alpha != null) {
            transform.setAlpha(alpha.doubleValue());
        }
        transform.setModel(modelClass);
        transform.setMatches(toFloats(p), toFloats(q), toFloats(w));

        LOG.info("call: exit");

        return transform;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MovingLeastSquaresBuilder.class);

}
