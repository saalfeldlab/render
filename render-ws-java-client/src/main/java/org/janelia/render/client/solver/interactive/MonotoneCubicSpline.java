package org.janelia.render.client.solver.interactive;

import java.util.ArrayList;
import java.util.List;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import net.imglib2.Point;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;

/**
 * n-dimensional extension of the monotone cubic spline implementation by Leszek Wach:
 * https://gist.github.com/lecho/7627739#file-splineinterpolation-java
 * 
 * @author Stephan Preibisch
 *
 */
public class MonotoneCubicSpline
{
	private final List<Double> mX;
	private final List< ? extends RealLocalizable > mY;
	private final double[][] mM;
	private final int nd;

	private MonotoneCubicSpline(final List<Double> x, final List< ? extends RealLocalizable > y, final double[][] m )
	{
		this.mX = x;
		this.mY = y;
		this.mM = m;
		this.nd = y.get( 0 ).numDimensions();
	}

	public static MonotoneCubicSpline createMonotoneCubicSpline( final List<? extends RealLocalizable> y )
	{
		if ( y == null || y.size() < 2 )
			throw new IllegalArgumentException("There must be at least two control points and the arrays must be of equal length.");

		final ArrayList< Double > x = new ArrayList<>();

		for ( int i = 0; i < y.size(); ++i )
			x.add( (double)i );

		return createMonotoneCubicSpline( x, y );
	}

	/*
	 * Creates a monotone cubic spline from a given set of control points.
	 * 
	 * The spline is guaranteed to pass through each control point exactly. Moreover, assuming the control points are
	 * monotonic (Y is non-decreasing or non-increasing) then the interpolated values will also be monotonic.
	 * 
	 * This function uses the Fritsch-Carlson method for computing the spline parameters.
	 * http://en.wikipedia.org/wiki/Monotone_cubic_interpolation
	 * 
	 * @param x
	 *            The X component of the control points, strictly increasing.
	 * @param y
	 *            The Y component of the control points
	 * @return
	 * 
	 * @throws IllegalArgumentException
	 *             if the X or Y arrays are null, have different lengths or have fewer than 2 values.
	 */
	public static MonotoneCubicSpline createMonotoneCubicSpline( final List<Double> x, final List<? extends RealLocalizable> y )
	{
		if (x == null || y == null || x.size() != y.size() || x.size() < 2) {
			throw new IllegalArgumentException("There must be at least two control "
					+ "points and the arrays must be of equal length.");
		}

		final int nd = y.get( 0 ).numDimensions();

		final int n = x.size();
		double[][] d = new double[n - 1][nd]; // could optimize this out
		double[][] m = new double[n][nd];

		// Compute slopes of secant lines between successive points.
		for (int i = 0; i < n - 1; i++)
		{
			final double h = x.get(i + 1) - x.get(i);
			if (h <= 0f)
				throw new IllegalArgumentException("The control points must all have strictly increasing X values.");

			for ( int dim = 0; dim < nd; ++dim )
				d[i][ dim ] = (y.get(i + 1).getDoublePosition( dim ) - y.get(i).getDoublePosition( dim )) / h;
		}

		// Initialize the tangents as the average of the secants.
		for ( int dim = 0; dim < nd; ++dim )
		{
			m[0][ dim ] = d[0][ dim ];
			for (int i = 1; i < n - 1; i++)
				m[i][ dim ] = (d[i - 1][ dim ] + d[i][ dim ]) * 0.5f;

			m[n - 1][ dim ] = d[n - 2][ dim ];

			// Update the tangents to preserve monotonicity.
			for (int i = 0; i < n - 1; i++)
			{
				if (d[i][ dim ] == 0f) { // successive Y values are equal
					m[i][ dim ] = 0f;
					m[i + 1][ dim ] = 0f;
				}
				else
				{
					final double a = m[i][ dim ] / d[i][ dim ];
					final double b = m[i + 1][ dim ] / d[i][ dim ];
					final double h = Math.hypot(a, b);
					if (h > 9f)
					{
						final double t = 3f / h;
						m[i][ dim ] = t * a * d[i][ dim ];
						m[i + 1][ dim ] = t * b * d[i][ dim ];
					}
				}
			}
		}

		return new MonotoneCubicSpline(x, y, m);
	}

	/*
	 * Interpolates the value of Y = f(X) for given X. Clamps X to the domain of the spline.
	 * 
	 * @param x
	 *            The X value.
	 * @return The interpolated Y = f(X) value.
	 */
	public < P extends RealPositionable > void interpolate( final double x, final P p )
	{
		// Handle the boundary cases.
		final int n = mX.size();
		if (Double.isNaN(x))
		{
			for ( int d = 0; d < nd; ++d )
				p.setPosition( 0, d );
			return;
		}

		if (x <= mX.get(0))
		{
			p.setPosition( mY.get( 0 ) );
			return;
		}

		if (x >= mX.get(n - 1))
		{
			p.setPosition( mY.get( n - 1 ) );
			return;
		}

		// Find the index 'i' of the last point with smaller X.
		// We know this will be within the spline due to the boundary tests.
		int i = 0;
		while (x >= mX.get(i + 1))
		{
			i += 1;
			if (x == mX.get(i))
			{
				p.setPosition( mY.get( i ) );
				return;
			}
		}

		// Perform cubic Hermite spline interpolation.
		for ( int dim = 0; dim < nd; ++dim )
		{
			final double h = mX.get(i + 1) - mX.get(i);
			final double t = (x - mX.get(i)) / h;

			p.setPosition( 
					(mY.get(i).getDoublePosition( dim ) * (1 + 2 * t) + h * mM[i][ dim ] * t) * (1 - t) * (1 - t)
					+ (mY.get(i + 1).getDoublePosition( dim ) * (3 - 2 * t) + h * mM[i + 1][ dim ] * (t - 1)) * t * t,
					dim );
		}
	}

	public static void main( String[] args )
	{
		final ArrayList< Point > points = new ArrayList<>();

		points.add( new Point( 79, 423 ) );
		points.add( new Point( 154,338 ) );
		points.add( new Point( 10,225 ) );
		points.add( new Point( 105, 142 ) );
		points.add( new Point( 234, 99 ) );
		points.add( new Point( 362, 145 ) );
		points.add( new Point( 397, 270 ) );
		points.add( new Point( 319, 337 ) );
		points.add( new Point( 346, 391 ) );
		points.add( new Point( 411, 441 ) );

		final MonotoneCubicSpline spline = MonotoneCubicSpline.createMonotoneCubicSpline( points );

		new ImageJ();

		final FloatProcessor fp = new FloatProcessor( 512, 512 );
		final RealPoint p = new RealPoint( points.get( 0 ).numDimensions() );

		for ( double x = 0; x < points.size() - 1; x += 0.001 )
		{
			spline.interpolate( x, p );

			fp.setf( Math.round( p.getFloatPosition( 0 ) ), Math.round( p.getFloatPosition( 1 ) ), 1 );
		}
		
		new ImagePlus( "test", fp ).show();
	}	
}