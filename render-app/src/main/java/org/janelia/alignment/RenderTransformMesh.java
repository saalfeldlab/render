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
package org.janelia.alignment;

import java.util.ArrayList;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.util.Pair;
import mpicbg.util.Util;

/**
 *
 * Triangular transformation mesh.
 *
 * See an example to find out how the mesh is constructed:
 *
 * numX = 4; numY = 3:
 * <pre>
 * *---*---*---*
 * |\ / \ / \ /|
 * | *---*---* |
 * |/ \ / \ / \|
 * *---*---*---*
 * |\ / \ / \ /|
 * | *---*---* |
 * |/ \ / \ / \|
 * *---*---*---*
 * </pre>
 *
 * Each vertex is listed at index <em>i</em> in a <code>double[][]</code> <em>pq</em> with
 * {@link PointMatch#getP1() p1} being the original point and
 * {@link PointMatch#getP2() p2} being the transferred point.  Keep in mind
 * that Points store local and world coordinates with local coordinates being
 * constant and world coordinates being mutable.  That is initially
 * {@link Point#getL() p1.l} = {@link Point#getW() p1.w} =
 * {@link Point#getL() p2.l} while {@link Point#getW() p1.w} is the transferred
 * location of the vertex.
 *
 * Three adjacent vertices span a triangle.  All pixels inside a triangle will
 * be transferred by a {@link AffineModel2D 2d affine transform} that is
 * defined by the three vertices.  Given the abovementioned definition of a
 * vertex as PointMatch, this {@link AffineModel2D 2d affine transform} is a
 * forward transform (p1.l->p2.w).
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class RenderTransformMesh implements InvertibleCoordinateTransform
{
    private static final long serialVersionUID = -5344324666263462355L;

    final public double[] unitWeights = new double[]{1.0, 1.0, 1.0};

    final protected double width, height;

    final protected double[] min, max;

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

	final protected ArrayList< Pair< AffineModel2D, double[][] > > av = new ArrayList< Pair< AffineModel2D, double[][] > >();
	public ArrayList< Pair< AffineModel2D, double[][] > > getAV(){ return av; }

    final static protected void initPoint(final double[] point, final double x, final double y) {
        point[0] = x;
        point[1] = y;
    }

    final static protected void addPointMatch(final int i, final double[][] pq, final double[] point, final CoordinateTransform t, final double x, final double y) {
        point[0] = x;
        point[1] = y;
        t.applyInPlace(point);

        pq[0][i] = x;
        pq[1][i] = y;
        pq[2][i] = point[0];
        pq[3][i] = point[1];
    }

    final static protected void addTriangle(
            final ArrayList< Pair< AffineModel2D, double[][] > > av,
            final double[][] pq,
            final int i1,
            final int i2,
            final int i3) {

        final double[][] pqTriangle = new double[][]{
                {pq[0][i1], pq[0][i2], pq[0][i3]},
                {pq[1][i1], pq[1][i2], pq[1][i3]},
                {pq[2][i1], pq[2][i2], pq[2][i3]},
                {pq[3][i1], pq[3][i2], pq[3][i3]}
        };

        av.add(new Pair<AffineModel2D, double[][]>(new AffineModel2D(), pqTriangle));
    }

    protected RenderTransformMesh(
            final CoordinateTransform t,
            final int numX,
            final int numY,
            final double width,
            final double height) {
        final int numXs = Math.max(2, numX);
        final int numYs = Math.max(2, numY);

        final double[][] pq = new double[4][numXs * numYs + (numXs - 1) * (numYs - 1)];
        final double[] pTemp = new double[2];

        this.width = width;
        this.height = height;

        final double dy = (height - 1) / (numYs - 1);
        final double dx = (width - 1) / (numXs - 1);

        int i = 0;

        for (int xi = 0; xi < numXs; ++xi) {

            final double xip = xi * dx;

            addPointMatch(i, pq, pTemp, t, xip, 0);

            ++i;
        }

        int i1, i2, i3;

        for (int yi = 1; yi < numYs; ++yi) {

            // odd row
            double yip = yi * dy - dy / 2;

            addPointMatch(i, pq, pTemp, t, dx - dx / 2, yip);

			i1 = i - numXs;
			i2 = i1 + 1;

			addTriangle(av, pq, i1, i2, i);

			++i;

			for ( int xi = 2; xi < numXs; ++xi )
			{
				final double xip = xi * dx - dx / 2;

				addPointMatch(i, pq, pTemp, t, xip, yip);

				i1 = i - numXs;
				i2 = i1 + 1;
				i3 = i - 1;

				addTriangle(av, pq, i1, i2, i);
				addTriangle(av, pq, i1, i, i3);

				++i;
			}

			// even row
			yip = yi * dy;

			addPointMatch(i, pq, pTemp, t, 0, yip);

			i1 = i - numXs + 1;
			i2 = i1 - numXs;

			addTriangle(av, pq, i2, i1, i);

			++i;

			for ( int xi = 1; xi < numXs - 1; ++xi )
			{
				final double xip = xi * dx;

				addPointMatch(i, pq, pTemp, t, xip, yip);

				i1 = i - numXs;
				i2 = i1 + 1;
				i3 = i - 1;

				addTriangle(av, pq, i1, i, i3);
				addTriangle(av, pq, i1, i2, i);

				++i;
			}

			addPointMatch(i, pq, pTemp, t, width - 1, yip);

			i1 = i - numXs;
			i2 = i1 - numXs + 1;
			i3 = i - 1;

			addTriangle(av, pq, i3, i1, i);
			addTriangle(av, pq, i1, i2, i);

			++i;
		}

        min = new double[]{pq[2][0], pq[3][0]};
        max = new double[]{pq[2][0], pq[3][0]};

        for (int j = 1; j < i; ++j) {
            final double x = pq[2][j];
            final double y = pq[3][j];
            if (x < min[0])
                min[0] = x;
            else if (x > max[0])
                max[0] = x;
            if (y < min[1])
                min[1] = y;
            else if (y > max[1])
                max[1] = y;
        }
	}

	final static protected int numY(
			final int numX,
			final double width,
			final double height )
	{
		final int numXs = Math.max( 2, numX );
		final double dx = width / ( numXs - 1 );
		final double dy = 2.0f * Math.sqrt( 3.0f / 4.0f * dx * dx );
		return Math.max( 2, Util.roundPos( height / dy ) + 1 );
	}

	public RenderTransformMesh(
	        final CoordinateTransform t,
			final int numX,
			final double width,
			final double height )
	{
		this( t, numX, numY( numX, width, height ), width, height );
	}

	/**
	 * Update all affine transformations.
	 */
	public void updateAffines()
	{
        for (final Pair<AffineModel2D, double[][]> apq : av) {
            final double[][] p = new double[][]{apq.b[0], apq.b[1]};
            final double[][] q = new double[][]{apq.b[2], apq.b[3]};
            try {
                apq.a.fit(p, q, unitWeights);
            } catch (final NotEnoughDataPointsException e) {
                e.printStackTrace();
            } catch (final IllDefinedDataPointsException e) {
                e.printStackTrace();
            }
        }
	}


	/**
     * Checks whether a location is inside a given triangle or not.
     *
     * @param ax
     * @param ay
     * @param bx
     * @param by
     * @param cx
     * @param cy
     * @param tx
     *            reference coordinate x
     * @param ty
     *            reference coordinate y
     *
     * @return
     */
    final static public boolean isInTriangle(
            final double ax, final double ay,
            final double bx, final double by,
            final double cx, final double cy,
            final double tx, final double ty) {

        final boolean d;
        {
            final double x1 = bx - ax;
            final double y1 = by - ay;
            final double x2 = tx - ax;
            final double y2 = ty - ay;
            d = x1 * y2 - y1 * x2 < 0;
        }
        {
            final double x1 = cx - bx;
            final double y1 = cy - by;
            final double x2 = tx - bx;
            final double y2 = ty - by;
            if ( d ^ x1 * y2 - y1 * x2 < 0 ) return false;
        }
        {
            final double x1 = ax - cx;
            final double y1 = ay - cy;
            final double x2 = tx - cx;
            final double y2 = ty - cy;
            if ( d ^ x1 * y2 - y1 * x2 < 0 ) return false;
        }
        return true;
    }

    /**
     * Checks whether a location is inside a given triangle at the target side
     * or not.
     *
     * @param pq
     *            triangle vertices
     * @param tx
     *            reference coordinate x
     * @param ty
     *            reference coordinate y
     *
     * @return
     */
    final static public boolean isInTargetTriangle(final double[][] pq, final double tx, final double ty) {

        return isInTriangle(pq[2][0], pq[3][0], pq[2][1], pq[3][1], pq[2][2], pq[3][2], tx, ty);
    }

    /**
     * Checks whether a location is inside a given triangle at the target side
     * or not.
     *
     * @param pq
     *            triangle vertices
     * @param t
     *            reference coordinate
     *
     * @return
     */
    final static public boolean isInTargetTriangle(final double[][] pq, final double[] t) {
        assert t.length == 2 : "2d transform meshs can be applied to 2d points only.";

        return isInTargetTriangle(pq, t[0], t[1]);
    }

    /**
     * Checks whether a location is inside a given triangle at the suorce side
     * or not.
     *
     * @param pq
     *            triangle vertices
     * @param tx
     *            reference coordinate x
     * @param ty
     *            reference coordinate y
     *
     * @return
     */
    final static public boolean isInSourceTriangle(final double[][] pq, final double tx, final double ty) {

        return isInTriangle(
                pq[0][0], pq[1][0],
                pq[0][1], pq[1][1],
                pq[0][2], pq[1][2],
                tx, ty);
    }

    /**
     * Checks whether a location is inside a given triangle at the target side
     * or not.
     *
     * @param pq
     *            triangle vertices
     * @param t
     *            reference coordinate
     *
     * @return
     */
    final static public boolean isInSourceTriangle(final double[][] pq, final double[] t) {
        assert t.length == 2 : "2d transform meshs can be applied to 2d points only.";

        return isInSourceTriangle(pq, t[0], t[1]);
    }


    final static public void calculateBoundingBox(
            final double[] xs,
            final double[] ys,
            final double[] min,
            final double[] max) {

        min[0] = xs[0];
        min[1] = ys[0];
        max[0] = xs[0];
        max[1] = ys[0];

        for (int i = 1; i < xs.length; ++i) {
            if (xs[i] < min[0])
                min[0] = xs[i];
            else if (xs[i] > max[0])
                max[0] = xs[i];
            if (ys[i] < min[1])
                min[1] = ys[i];
            else if (ys[i] > max[1])
                max[1] = ys[i];
        }
    }

    /**
     *
     * @param pq
     *            PointMatches [{p<sub>x</sub>, p<sub>y</sub>, q<sub>x</sub>,
     *            q<sub>y</sub><literal>}]
     * @param min
     *            x = min[0], y = min[1]
     * @param max
     *            x = max[0], y = max[1]
     */
    final static public void calculateTargetBoundingBox(final double[][] pq, final double[] min, final double[] max) {

        calculateBoundingBox(pq[2], pq[3], min, max);
    }

    /**
     *
     * @param pq
     *            PointMatches [{p<sub>x</sub>, p<sub>y</sub>, q<sub>x</sub>,
     *            q<sub>y</sub><literal>}]
     * @param min
     *            x = min[0], y = min[1]
     * @param max
     *            x = max[0], y = max[1]
     */
    final static public void calculateSourceBoundingBox(final double[][] pq, final double[] min, final double[] max) {

        calculateBoundingBox(pq[0], pq[1], min, max);
    }


    @Override
    public double[] apply(final double[] location) {
        assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";

        final double[] transformed = location.clone();
        applyInPlace(transformed);
        return transformed;
    }

    @Override
    public void applyInPlace(final double[] location) {
        assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";

        for (final Pair<AffineModel2D, double[][]> apq : av) {
            if (isInSourceTriangle(apq.b, location)) {
                apq.a.applyInPlace(location);
                return;
            }
        }
    }

    @Override
    public double[] applyInverse(final double[] location) throws NoninvertibleModelException {
        assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";

        final double[] transformed = location.clone();
        applyInverseInPlace(transformed);
        return transformed;
    }

    @Override
    public void applyInverseInPlace(final double[] location) throws NoninvertibleModelException {
        assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";

        for (final Pair<AffineModel2D, double[][]> apq : av) {
            if (isInTargetTriangle(apq.b, location)) {
                apq.a.applyInPlace(location);
                return;
            }
        }
        throw new NoninvertibleModelException("Noninvertible location ( " + location[0] + ", " + location[1] + " )");
    }

	@Override
	public RenderTransformMesh createInverse()
	{
		throw new UnsupportedOperationException();
	}

    /**
     * Scale all vertex coordinates
     *
     * @param scale
     */
    public void scale(final double scale) {
        for (final Pair<AffineModel2D, double[][]> apq : av)
            for (int i = 0; i < apq.b.length; ++i)
                for (int j = 0; j < apq.b[i].length; ++j)
                    apq.b[i][j] *= scale;
    }

    /**
     * Scale all vertex coordinates in target space
     *
     * @param scale
     */
    public void scaleTarget(final double scale) {
        for (final Pair<AffineModel2D, double[][]> apq : av)
            for (int j = 0; j < apq.b[2].length; ++j) {
                apq.b[2][j] *= scale;
                apq.b[3][j] *= scale;
            }
    }

    /**
     * Translate all vertex coordinates
     *
     * @param scale
     */
    public void translate(final double x, final double y) {
        for (final Pair<AffineModel2D, double[][]> apq : av)
            for (int j = 0; j < apq.b[2].length; ++j) {
                apq.b[0][j] += x;
                apq.b[1][j] += y;
                apq.b[2][j] += x;
                apq.b[3][j] += y;
            }
    }

    /**
     * Scale all vertex coordinates
     *
     * @param scale
     */
    public void translateTarget(final double x, final double y) {
        for (final Pair<AffineModel2D, double[][]> apq : av)
            for (int j = 0; j < apq.b[2].length; ++j) {
                apq.b[2][j] += x;
                apq.b[3][j] += y;
            }
    }

    /**
     * Get bounding box
     *
     * @param min
     * @param max
     */
    public void bounds(@SuppressWarnings("hiding") final double[] min, @SuppressWarnings("hiding") final double[] max) {
        min[0] = this.min[0];
        min[1] = this.min[1];
        max[0] = this.max[0];
        max[1] = this.max[1];
    }
}
