package org.janelia.alignment;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import mpicbg.trakem2.transform.TranslationModel2D;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 */
public class MovingLeastSquaresBuilder {

    /** List of deformation control points. */
    private List<PointMatch> montageToAlignCenterPointMatches;

    /** MLS transform model class (based upon number of point matches) */
    private Class<? extends AbstractAffineModel2D<?>> modelClass;

    /**
     * Derives center point matches between the two specified tile lists using tileId to correlate the tiles.
     * The {@link #build} method can then be used to derive a moving least squares transform instance
     * from the point matches.
     *
     * @param  montageTiles  collection of tiles from a montage stack.
     * @param  alignTiles    collection of tiles from an align stack.
     */
    public MovingLeastSquaresBuilder(final Collection<TileSpec> montageTiles,
                                     final Collection<TileSpec> alignTiles) {

        LOG.info("ctor: entry, processing {} montageTiles and {} alignTiles",
                 montageTiles.size(), alignTiles.size());

        final int initialCapacity = alignTiles.size() * 2; // make sure we have enough buckets with default load factor
        final Map<String, TileSpec> alignTileIdToSpecMap = new HashMap<String, TileSpec>(initialCapacity);
        for (TileSpec alignTile : alignTiles) {
            alignTileIdToSpecMap.put(alignTile.getTileId(), alignTile);
        }

        this.montageToAlignCenterPointMatches = new ArrayList<PointMatch>(alignTiles.size());

        String tileId;
        TileSpec alignTile;
        Point montageCenterPoint;
        Point alignCenterPoint;
        for (TileSpec montageTile : montageTiles) {

            tileId = montageTile.getTileId();
            alignTile = alignTileIdToSpecMap.get(tileId);

            if (alignTile != null) {
                // create one deformation control point at the center point of each tile
                montageCenterPoint = new Point(centerToWorld(montageTile));
                alignCenterPoint = new Point(centerToWorld(alignTile));
                this.montageToAlignCenterPointMatches.add(new PointMatch(montageCenterPoint, alignCenterPoint));
            }
        }

        final int numberOfPointMatches = montageToAlignCenterPointMatches.size();
        if (numberOfPointMatches == 0) {
            throw new IllegalArgumentException("montage and align tile lists contain no common tile ids");
        } else if (numberOfPointMatches == 1) {
            this.modelClass = TranslationModel2D.class;
        } else if (numberOfPointMatches == 2) {
            this.modelClass = SimilarityModel2D.class;
        } else {
            this.modelClass = AffineModel2D.class;
        }

        LOG.info("ctor: exit, derived {} point matches", numberOfPointMatches);
    }

    /**
     * @param  alpha  transform alpha (optional, default is 1.0)
     *
     * @return an MLS transform instance using this builder's point matches.
     */
    public MovingLeastSquaresTransform2 build(final Double alpha) throws Exception {

        LOG.info("build: entry");

        final MovingLeastSquaresTransform2 transform = new MovingLeastSquaresTransform2();

        if (alpha != null) {
            transform.setAlpha(alpha.floatValue());
        }
        transform.setModel(modelClass);
        transform.setMatches(montageToAlignCenterPointMatches);

        LOG.info("build: exit");

        return transform;
    }

    private static float[] centerToWorld(final TileSpec tileSpec) {
        final float[] center = new float[] { (float) tileSpec.getWidth() / 2.0f, (float) tileSpec.getHeight() / 2.0f };
        final CoordinateTransformList<CoordinateTransform> ctl = tileSpec.getTransformList();
        ctl.applyInPlace(center);
        return center;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MovingLeastSquaresBuilder.class);

}
