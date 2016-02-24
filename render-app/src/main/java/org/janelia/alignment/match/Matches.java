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

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Collection of n-dimensional weighted source-target point correspondences.
 * Both point lists are stored as double[][]
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org> and John Bogovic
 */
@ApiModel(description = "A collection of n-dimensional weighted source-target point correspondences.")
@XmlAccessorType(XmlAccessType.FIELD)
public class Matches implements Serializable {

    private static final long serialVersionUID = 7877702099321390264L;

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
    @ApiModelProperty(value = "Source point coordinates", required=true)
    final protected double[][] p;

    /** target point coordinates */
    @ApiModelProperty(value = "Target point coordinates", required=true)
    final protected double[][] q;

    /** weights */
    @ApiModelProperty(value = "Weights", required=true)
    final protected double[] w;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private Matches() {
        this.p = null;
        this.q = null;
        this.w = null;
    }

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
                new Point(new double[]{p[0][i], p[1][i]}),
                new Point(new double[]{q[0][i], q[1][i]}),
                w[i]);
    }
    public ArrayList<PointMatch> createPointMatches() {
        final ArrayList<PointMatch> matches = new ArrayList<>();
        for (int i = 0; i < w.length; ++i) {
            matches.add(createPointMatch(i));
        }
        return matches;
    }
}