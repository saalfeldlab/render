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
 * The warp is realized with a transformation (implemented within {@link #call})
 * per layer with the center points of all tiles being the control points.
 * It is important to understand that the warping has no effect on the smoothness
 * of transitions between patches within the montage.
 *
 * The result is a project with layer montages that are warped such that they
 * resemble the alignment result of the multi-layer-mosaic alignment of the
 * second project. This result is applied to the first project.
 *
 * @author Eric Trautman
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
abstract public class AbstractWarpTransformBuilder<T extends CoordinateTransform> implements Callable<T>
{
    protected double[][] p;
    protected double[][] q;
    protected double[] w;

    protected static double[] centerToWorld(final TileSpec tileSpec) {
        final double[] center = new double[] { tileSpec.getWidth() / 2.0, tileSpec.getHeight() / 2.0 };
        final CoordinateTransformList<CoordinateTransform> ctl = tileSpec.getTransformList();
        ctl.applyInPlace(center);
        return center;
    }

    protected void readTileSpecs(
            final Collection<TileSpec> montageTiles,
            final Collection<TileSpec> alignTiles) {

        LOG.info("readTileSpecs: entry, processing {} montageTiles and {} alignTiles",
                 montageTiles.size(), alignTiles.size());

        final int initialCapacity = alignTiles.size() * 2; // make sure we have enough buckets with default load factor
        final Map<String, TileSpec> alignTileIdToSpecMap = new HashMap<>(initialCapacity);
        for (final TileSpec alignTile : alignTiles) {
            alignTileIdToSpecMap.put(alignTile.getTileId(), alignTile);
        }

        final ArrayList<PointMatch> montageToAlignCenterPointMatches = new ArrayList<>(alignTiles.size());

        final Map<String, TileSpec> centerToMontageTileMap = new HashMap<>(montageTiles.size() * 2);
        TileSpec montageTileWithSameCenter;
        int duplicateCenterTileCount = 0;

        String tileId;
        TileSpec alignTile;
        double[] montageCenterCoordinates;
        String montageCenterString;
        Point montageCenterPoint;
        Point alignCenterPoint;
        for (final TileSpec montageTile : montageTiles) {

            tileId = montageTile.getTileId();
            alignTile = alignTileIdToSpecMap.get(tileId);

            if (alignTile != null) {
                // create one deformation control point at the center point of each tile

                // first, validate each montage center point is unique
                montageCenterCoordinates = centerToWorld(montageTile);
                montageCenterString = deriveString(montageCenterCoordinates);
                montageTileWithSameCenter = centerToMontageTileMap.put(montageCenterString, montageTile);
                if (montageTileWithSameCenter != null) {
                    duplicateCenterTileCount++;
                    LOG.warn("montage tile {} has the same center ({}) as tile {}",
                             montageTile.getTileId(), montageCenterString, montageTileWithSameCenter.getTileId());
                    // restore original tile
                    centerToMontageTileMap.put(montageCenterString, montageTileWithSameCenter);
                }

                montageCenterPoint = new Point(montageCenterCoordinates);
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

        if (duplicateCenterTileCount > 0) {
            throw new IllegalArgumentException(
                    duplicateCenterTileCount + " montage tile(s) have the same center coordinates");
        }

        p = new double[2][numberOfPointMatches];
        q = new double[2][numberOfPointMatches];
        w = new double[numberOfPointMatches];

        int i = 0;
        for (final PointMatch match : montageToAlignCenterPointMatches) {
            final double[] pp = match.getP1().getL();
            final double[] qq = match.getP2().getW();

            p[0][i] = pp[0];
            p[1][i] = pp[1];

            q[0][i] = qq[0];
            q[1][i] = qq[1];

            w[i] = match.getWeight();

            ++i;
        }

        LOG.info("readTileSpecs: exit, derived {} point matches", numberOfPointMatches);
    }

    private String deriveString(final double[] coordinate) {
        final StringBuilder sb = new StringBuilder(64);
        if (coordinate.length > 0) {
            sb.append(coordinate[0]);
            for (int i = 1; i < coordinate.length; i++) {
                sb.append(',');
                sb.append(coordinate[i]);
            }
        }
        return sb.toString();
    }

    private static final Logger LOG = LoggerFactory.getLogger(AbstractWarpTransformBuilder.class);
}
