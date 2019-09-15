package org.janelia.alignment.warp;

import java.util.Collection;

import mpicbg.trakem2.transform.RigidModel2D;

import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AbstractWarpTransformBuilder} implementation that realizes the "warp" (see {@link #call})
 * with a Rigid 2D transformation.
 *
 * @author Eric Trautman
 */
public class RigidBuilder
        extends AbstractWarpTransformBuilder<RigidModel2D> {

    /**
     * Derives center point matches between the two specified tile lists using tileId to correlate the tiles.
     * The {@link #call} method can then be used to derive a thin plate spline transform instance
     * from the point matches.
     *
     * @param  montageTiles  collection of tiles from a montage stack.
     * @param  alignTiles    collection of tiles from an align stack.
     */
    public RigidBuilder(final Collection<TileSpec> montageTiles,
                        final Collection<TileSpec> alignTiles) {
        
        readTileSpecs(montageTiles, alignTiles);
    }

    /**
     * @return a TPS transform instance using this builder's point matches.
     */
    @Override
    public RigidModel2D call() throws Exception {

        LOG.info("call: entry");

        final RigidModel2D t = new RigidModel2D();
        t.fit(p, q, w);

        LOG.info("call: exit");

        return t;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RigidBuilder.class);

}
