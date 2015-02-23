/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment.warp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 *
 * @author Eric Trautman
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
abstract public class AbstractWarpTransformBuilder<T extends CoordinateTransform> implements Callable<T>
{
    protected float[][] p;
    protected float[][] q;
    protected float[] w;
    
    protected static float[] centerToWorld(final TileSpec tileSpec) {
        final float[] center = new float[] { (float) tileSpec.getWidth() / 2.0f, (float) tileSpec.getHeight() / 2.0f };
        final CoordinateTransformList<CoordinateTransform> ctl = tileSpec.getTransformList();
        ctl.applyInPlace(center);
        return center;
    }
    
    protected void readTileSpecs(
            final Collection<TileSpec> montageTiles,
            final Collection<TileSpec> alignTiles) {
        LOG.info(
                "ctor: entry, processing {} montageTiles and {} alignTiles",
                montageTiles.size(), alignTiles.size());

        final int initialCapacity = alignTiles.size() * 2; // make sure we have enough buckets with default load factor
        final Map<String, TileSpec> alignTileIdToSpecMap = new HashMap<String, TileSpec>(initialCapacity);
        for (final TileSpec alignTile : alignTiles) {
            alignTileIdToSpecMap.put(alignTile.getTileId(), alignTile);
        }

        final ArrayList<PointMatch> montageToAlignCenterPointMatches = new ArrayList<PointMatch>(alignTiles.size());

        String tileId;
        TileSpec alignTile;
        Point montageCenterPoint;
        Point alignCenterPoint;
        for (final TileSpec montageTile : montageTiles) {

            tileId = montageTile.getTileId();
            alignTile = alignTileIdToSpecMap.get(tileId);

            if (alignTile != null) {
                // create one deformation control point at the center point of
                // each tile
                montageCenterPoint = new Point(centerToWorld(montageTile));
                alignCenterPoint = new Point(centerToWorld(alignTile));
                montageToAlignCenterPointMatches.add(new PointMatch(
                        montageCenterPoint, alignCenterPoint));
            }
        }

        final int numberOfPointMatches = montageToAlignCenterPointMatches.size();
        if (numberOfPointMatches == 0) {
            throw new IllegalArgumentException(
                    "montage and align tile lists do not contain common tile ids");
        }
        
        p = new float[2][numberOfPointMatches];
        q = new float[2][numberOfPointMatches];
        w = new float[numberOfPointMatches];
        
        int i = 0;
        for (final PointMatch match : montageToAlignCenterPointMatches) {
            final float[] pp = match.getP1().getL();
            final float[] qq = match.getP2().getW();

            p[0][i] = pp[0];
            p[1][i] = pp[1];

            q[0][i] = qq[0];
            q[1][i] = qq[1];

            w[i] = match.getWeight();

            ++i;
        }

        LOG.info("ctor: exit, derived {} point matches", numberOfPointMatches);
    }
	
    private static final Logger LOG = LoggerFactory.getLogger(AbstractWarpTransformBuilder.class);
}
