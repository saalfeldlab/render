package org.janelia.alignment.warp;

import java.util.Collection;

import jitk.spline.ThinPlateR2LogRSplineKernelTransform;
import mpicbg.trakem2.transform.ThinPlateSplineTransform;

import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class ThinPlateSplineBuilder extends AbstractWarpTransformBuilder<ThinPlateSplineTransform> {

    /**
     * Derives center point matches between the two specified tile lists using tileId to correlate the tiles.
     * The {@link #build} method can then be used to derive a moving least squares transform instance
     * from the point matches.
     *
     * @param  montageTiles  collection of tiles from a montage stack.
     * @param  alignTiles    collection of tiles from an align stack.
     */
    public ThinPlateSplineBuilder(final Collection<TileSpec> montageTiles,
                                     final Collection<TileSpec> alignTiles) {
        
        readTileSpecs(montageTiles, alignTiles);

        LOG.info("ctor: exit, derived {} point matches", w.length);
    }

    /**
     * @return a TPS transform instance using this builder's point matches.
     */
    @Override
    public ThinPlateSplineTransform call() throws Exception {

        LOG.info("build: entry");

        final ThinPlateR2LogRSplineKernelTransform tpsKernelTransform =
                new ThinPlateR2LogRSplineKernelTransform(2, p, q);
        
        tpsKernelTransform.solve();
        
        final ThinPlateSplineTransform t = new ThinPlateSplineTransform(tpsKernelTransform);

        LOG.info("build: exit");

        return t;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ThinPlateSplineBuilder.class);

}
