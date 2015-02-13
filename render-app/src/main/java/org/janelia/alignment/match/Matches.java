/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
/**
 * Collection of n-dimensional weighted source-target point correspondences.
 * Both point lists are stored as double[][]
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org> and John Bogovic
 */
@SuppressWarnings("UnusedDeclaration")
public class Matches implements Serializable {
    private static boolean checkDimensions(
            final double[][] p,
            final double[][] q,
            final double[] w) {
        if (p.length == q.length && p.length > 0 && q.length > 0) {
            for (int d = 0; d < p.length; ++d)
                if (!(w.length == p[d].length && w.length == q[d].length))
                    return false;
        } else
            return false;
        return true;
    }
    /** source point coordinates */
    final protected double[][] p;
    /** target point coordinates */
    final protected double[][] q;
    /** weights */
    final protected double[] w;
    public Matches(
            final double[][] p,
            final double[][] q,
            final double[] w)
    {
        assert (checkDimensions(p, q, w)) : "Dimensions do not match!";
        this.p = p;
        this.q = q;
        this.w = w;
    }
    public double[][] getPs()
    {
        return p;
    }
    public double[][] getQs()
    {
        return q;
    }
    public double[] getWs()
    {
        return w;
    }

    /* mpicbg legacy helper until it's getting better... */
    public float[][] createFloatPs()
    {
        final float[][] floatPs = new float[p.length][p[0].length];
        for (int d = 0; d < p.length; ++d)
            for (int i = 0; i < w.length; ++i)
                floatPs[d][i] = (float)p[d][i];
        return floatPs;
    }
    public float[][] createFloatQs()
    {
        final float[][] floatQs = new float[q.length][q[0].length];
        for (int d = 0; d < q.length; ++d)
            for (int i = 0; i < w.length; ++i)
                floatQs[d][i] = (float)q[d][i];
        return floatQs;
    }
    public float[] createFloatWs()
    {
        final float[] floatWs = new float[w.length];
        for (int i = 0; i < w.length; ++i)
            floatWs[i] = (float)w[i];
        return floatWs;
    }
    public PointMatch createPointMatch(final int i) {
        return new PointMatch(
                new Point(new float[]{(float)p[0][i], (float)p[1][i]}),
                new Point(new float[]{(float)q[0][i], (float)q[1][i]}),
                (float)w[i]);
    }
    public ArrayList<PointMatch> createPointMatches() {
        final ArrayList<PointMatch> matches = new ArrayList<>();
        for (int i = 0; i < w.length; ++i) {
            matches.add(createPointMatch(i));
        }
        return matches;
    }
}