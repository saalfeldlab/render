package org.janelia.alignment.warp;

import java.util.Collection;

import jitk.spline.ThinPlateR2LogRSplineKernelTransform;
import mpicbg.trakem2.transform.ThinPlateSplineTransform;

import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AbstractWarpTransformBuilder} implementation that realizes the warp (see {@link #call})
 * with a Thin Plate Spline transformation.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class ThinPlateSplineBuilder extends AbstractWarpTransformBuilder<ThinPlateSplineTransform> {

    /**
     * Derives center point matches between the two specified tile lists using tileId to correlate the tiles.
     * The {@link #call} method can then be used to derive a thin plate spline transform instance
     * from the point matches.
     *
     * @param  montageTiles  collection of tiles from a montage stack.
     * @param  alignTiles    collection of tiles from an align stack.
     */
    public ThinPlateSplineBuilder(final Collection<TileSpec> montageTiles,
                                  final Collection<TileSpec> alignTiles) {
        
        readTileSpecs(montageTiles, alignTiles);
    }

    /**
     * @return a TPS transform instance using this builder's point matches.
     */
    @Override
    public ThinPlateSplineTransform call() throws Exception {

        LOG.info("call: entry");

        final ThinPlateR2LogRSplineKernelTransform tpsKernelTransform =
                new ThinPlateR2LogRSplineKernelTransform(2, p, q);
        
        tpsKernelTransform.solve();

        final double[][] TPSAffine = tpsKernelTransform.getAffine();
        for (int j = 0; j < TPSAffine.length; j++) {
            for (int i = 0; i < TPSAffine[j].length; i++) {
                if (Double.isNaN(TPSAffine[j][i])) {
                    throw new IllegalStateException("TPSAffine[" + j + "][" + i +
                                                    "] is NaN.  Two montage tiles may have the same center point.");
                }
            }
        }
        
        final ThinPlateSplineTransform t = new ThinPlateSplineTransform(tpsKernelTransform);

        LOG.info("call: exit");

        return t;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ThinPlateSplineBuilder.class);

}
