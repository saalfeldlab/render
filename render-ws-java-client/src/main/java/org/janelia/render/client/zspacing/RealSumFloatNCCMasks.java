package org.janelia.render.client.zspacing;

import java.util.concurrent.Callable;

import net.imglib2.util.RealSum;

/**
 * @author Philipp Hanslovsky &lt;hanslovskyp@janelia.hhmi.org&gt;
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 * @author spreibi
 *
 */
public class RealSumFloatNCCMasks  implements Callable< Double >
{
	protected final float[] ap, bp, am, bm;

	/**
	 * 
	 * @param ap - pixels of image a
	 * @param am - mask for image a, everything below 255 will be ignored
	 * @param bp - pixels of image a
	 * @param bm - mask for image b, everything below 255 will be ignored
	 */
	public RealSumFloatNCCMasks( final float[] ap, final float[] am, final float[] bp, final float[] bm  )
	{
		this.ap = ap;
		this.am = am;
		this.bp = bp;
		this.bm = bm;
	}

	@Override
	public Double call()
	{
		final RealSum sumA = new RealSum();
		final RealSum sumAA = new RealSum();
		final RealSum sumB = new RealSum();
		final RealSum sumBB = new RealSum();
		final RealSum sumAB = new RealSum();
		int n = 0;
		for ( int i = 0; i < ap.length; ++i )
		{
			if ( am[ i ] < 255 || bm[ i ] < 255 )
				continue;

			final double va = ap[ i ];
			final double vb = bp[ i ];

			if ( Double.isNaN( va ) || Double.isNaN( vb ) )
				continue;

			// TODO: Remove once MaskedResinLoader mask is fixed
			//if ( va <= 0 || vb <= 0 )
			//	continue;

			++n;
			sumA.add( va );
			sumAA.add( va * va );
			sumB.add( vb );
			sumBB.add( vb * vb );
			sumAB.add( va * vb );
		}
		final double suma = sumA.getSum();
		final double sumaa = sumAA.getSum();
		final double sumb = sumB.getSum();
		final double sumbb = sumBB.getSum();
		final double sumab = sumAB.getSum();

		if ( n == 0 )
			return 0.0;
		else
			return ( n * sumab - suma * sumb ) / Math.sqrt( n * sumaa - suma * suma ) / Math.sqrt( n * sumbb - sumb * sumb );
	}
}
