package org.janelia.alignment.match;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.CurveFitter;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;

import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.parameters.CrossCorrelationParameters;
import org.janelia.alignment.transform.ExponentialRecoveryOffsetTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fit.PointFunctionMatch;
import fit.polynomial.HigherOrderPolynomialFunction;

/**
 * Calculates deformation of a tile by looking at overlapping area of an adjacent "correct" tile.
 *
 * @author Stephan Preibisch
 */
public class UnscaleTile {

	public static class FitResult {
		private final CurveFitter cfFullRes;
		private final CurveFitter cfRenderScale;
		private final int numInliers;

		public FitResult(final CurveFitter cfFullRes,
						 final CurveFitter cfRenderScale,
						 final Integer numInliers) {
			this.cfFullRes = cfFullRes;
			this.cfRenderScale = cfRenderScale;
			this.numInliers = numInliers;
		}

		/**
		 * Model needed for actual processing
		 */
		@JsonIgnore
		public CurveFitter getCfFullRes() {
			return cfFullRes;
		}

		/**
		 * Model to apply to local test images
		 */
		@JsonIgnore
		public CurveFitter getCfRenderScale() {
			return cfRenderScale;
		}

		/**
		 * How many points (cross-correlation ROIs) were used for the fit, should be > 500 at render scale 0.25.
		 * Here we use a 3rd order polynomial for separating good points from outliers.
		 */
		@JsonProperty("numInliers")
		public int getNumInliers() {
			return numInliers;
		}

		/**
		 * @return the goodness of the full scale fit of the exponential decay on the inliers
		 *         (should be ~0.99, is between 0 and 1).
		 */
		@JsonProperty("goodnessOfFit")
		public double getGoodnessOfFit() {
			return cfFullRes.getFitGoodness();
		}

		/**
		 * @return the scaling at y=0 (maybe -50 for a strongly deformed one, maybe -2 for a "correct" one)
		 */
		@SuppressWarnings("unused")
		@JsonProperty("valueAt0")
		public double getValueAt0() {
			return cfFullRes.f( 0 );
		}

		/**
		 * @return offset transform built from full resolution curve fitter.
		 */
		@JsonIgnore
		public ExponentialRecoveryOffsetTransform buildTransform() {
			ExponentialRecoveryOffsetTransform transform = null;
			final double[] parameters = cfFullRes.getParams();
			if (parameters.length >= 3) {
				transform = new ExponentialRecoveryOffsetTransform(parameters[0],
																   parameters[1],
																   parameters[2],
																   1); // transform y
			}
			return transform;
		}

		public String toJson() {
			return FIT_RESULT_JSON_HELPER.toJson(this);
		}

		@Override
		public String toString() {
			return this.toJson();
		}
	}

	public static FitResult getScalingFunction(
			final ImagePlus ip1,
			final ImageProcessor mask1,
			final ImagePlus ip2,
			final ImageProcessor mask2,
			final boolean visualizeSampleRois,
			final double renderScale,
			final CrossCorrelationParameters ccParameters )
	{

		final int scaledSampleSize = ccParameters.getScaledSampleSize(renderScale);
		final int scaledStepSize = ccParameters.getScaledStepSize(renderScale);

		final Rectangle unmaskedArea1 = mask1 == null ? new Rectangle(ip1.getWidth(), ip1.getHeight())
				: findRectangle(mask1);
		final Rectangle unmaskedArea2 = mask2 == null ? new Rectangle(ip2.getWidth(), ip2.getHeight())
				: findRectangle(mask2);

		// only stepThroughY works
		final boolean stepThroughY = ip1.getHeight() > ip1.getWidth();

		final int startStep;
		final int maxHeightOrWidth;
		if (stepThroughY) {
			startStep = Math.min(unmaskedArea1.y, unmaskedArea2.y);
			maxHeightOrWidth = Math.max(unmaskedArea1.y + unmaskedArea1.height - 1,
					unmaskedArea2.y + unmaskedArea2.height - 1);
		} else {
			startStep = Math.min(unmaskedArea1.x, unmaskedArea2.x);
			maxHeightOrWidth = Math.max(unmaskedArea1.x + unmaskedArea1.width - 1,
					unmaskedArea2.x + unmaskedArea2.width - 1);
		}
		final int endStep = maxHeightOrWidth - startStep - scaledSampleSize + scaledStepSize + 1;
		final int numTests = (endStep / scaledStepSize) + Math.min(1, (endStep % scaledStepSize));
		final double stepIncrement = endStep / (double) numTests;

		LOG.debug(
				"getScalingFunction: renderScale={}, minResultThreshold={}, scaledSampleSize={}, scaledStepSize={}, numTests={}, stepIncrement={}",
				renderScale, ccParameters.minResultThreshold, scaledSampleSize, scaledStepSize, numTests,
				stepIncrement);

		final int numTestsDiv4 = numTests / 4;
		final ArrayList<PointFunctionMatch> matches = new ArrayList<>();

		for (int i = 0; i < numTests; ++i) {

			final int minXOrY = (int) Math.round(i * stepIncrement) + startStep;
			final int maxXOrY = minXOrY + scaledSampleSize - 1;
			final int sampleWidthOrHeight = maxXOrY - minXOrY + 1;

			final Rectangle r1PCM, r2PCM;
			if (stepThroughY) {
				r1PCM = new Rectangle(unmaskedArea1.x, minXOrY, unmaskedArea1.width, sampleWidthOrHeight);
				r2PCM = new Rectangle(unmaskedArea2.x, minXOrY, unmaskedArea2.width, sampleWidthOrHeight);
			} else {
				r1PCM = new Rectangle(minXOrY, unmaskedArea1.y, sampleWidthOrHeight, unmaskedArea1.height);
				r2PCM = new Rectangle(minXOrY, unmaskedArea2.y, sampleWidthOrHeight, unmaskedArea2.height);
			}

			final Roi roi1 = new Roi(r1PCM);
			final Roi roi2 = new Roi(r2PCM);

			if (visualizeSampleRois) {
				ip1.setRoi(roi1);
				ip2.setRoi(roi2);
			}

			final StitchingParameters params = ccParameters.toStitchingParameters();

			final PairWiseStitchingResult result = PairWiseStitchingImgLib.stitchPairwise(ip1, ip2, roi1, roi2, 1, 1,
					params);

			if (result.getCrossCorrelation() >= ccParameters.minResultThreshold) {

				//LOG.debug(minXOrY + " > " + maxXOrY + ", shift : " + Util.printCoordinates(result.getOffset())
				//		+ ", correlation (R)=" + result.getCrossCorrelation());

				matches.add( new PointFunctionMatch( new Point( new double[] {minXOrY + r1PCM.height/2,result.getOffset(1)})));
			}

			if ((i > 0) && (i % numTestsDiv4 == 0)) {
				LOG.debug("getScalingFunction: finished test {} of {}", i, numTests);
			}
		}

		final ArrayList<PointFunctionMatch> inliers = new ArrayList<>();
		//final QuadraticFunction qf = new QuadraticFunction();
		final HigherOrderPolynomialFunction hpf = new HigherOrderPolynomialFunction( 3 );
		double maxCF = -Double.MAX_VALUE;
		CurveFitter cfRenderScale = null;

		try {
			LOG.debug("getScalingFunction: filter matches");
			int minX = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
//			HashMap<Integer, Double> matchMap = new HashMap<>();
			for ( final PointFunctionMatch pm : matches )
			{
				final int x = (int)Math.round(pm.getP1().getL()[0]);
				minX = Math.min( x, minX );
				maxX = Math.max( x, maxX );
//				matchMap.put(x, pm.getP1().getL()[1]);
			}
//			System.out.println("minX="+minX + " maxX="+maxX);

			// ransac using 3rd order polynomial on all matches
			// error = 2.5, 25% inlier ratio
			hpf.filterRansac( matches, inliers, 1000, 2.5*renderScale, 0.25);
//			System.out.println(inliers.size()+ ", " + hpf);
			if ( inliers.size() < 4 ) {
				LOG.warn("getScalingFunction: only found " + inliers.size() + " inliers, returning null result");
				return null;
			}

			Collections.sort(inliers, (o1,o2) -> (int)Math.round(o1.getP1().getL()[0]) - (int)Math.round(o2.getP1().getL()[0]));

			final double[] xData = new double[ inliers.size() ];
			final double[] yData = new double[ inliers.size() ];
			int j = 0;
			for ( final PointFunctionMatch pm : inliers )
			{
				//System.out.println(pm.getP1().getL()[0]+","+(pm.getP1().getL()[1]));
				xData[ j ] = pm.getP1().getL()[0];
				yData[ j ] = pm.getP1().getL()[1];
				++j;
			}

			LOG.debug("getScalingFunction: first fit for {} inliers at render scale {}", inliers.size(), renderScale);

			//System.out.println( inliers.size() + ", " + hpf );
			//for ( int x = 1; x <= 812;++x)
			//	System.out.println( x+","+hpf.predict( x ) );
			cfRenderScale = new CurveFitter(xData, yData);
			cfRenderScale.doFit( CurveFitter.EXP_RECOVERY );
//			System.out.println( cfRenderScale.getFormula() );
//			System.out.println( cfRenderScale.getFitGoodness() );
//			System.out.println( cfRenderScale.getResultString() );

			for ( int x = minX; x <= maxX;++x)
				maxCF = Math.max( maxCF, cfRenderScale.f(x) );
//			System.out.println( "maxCF=" + maxCF );

			// make sure the scaling base is 0
			for ( j = 0; j < yData.length; ++j )
				yData[ j ] -= maxCF;

			cfRenderScale = new CurveFitter(xData, yData);
			cfRenderScale.doFit( CurveFitter.EXP_RECOVERY );

//			System.out.println( cfRenderScale.getResultString() );

			/*
			for ( int x = minX; x <= maxX;++x)
				System.out.println( x+","+(matchMap.containsKey(x) ? (matchMap.get(x)-maxCF) : "")+","+(cfRenderScale.f(x)));
			*/
		} catch (final NotEnoughDataPointsException e) {
			LOG.warn("getScalingFunction: failed to filter " + inliers.size() + " inliers, returning null result", e);
			return null;
		}

		//
		// build full-res fit
		//
		final double[] xData = new double[ inliers.size() ];
		final double[] yData = new double[ inliers.size() ];
		int j = 0;

		for ( final PointFunctionMatch pm : inliers )
		{
			xData[ j ] = pm.getP1().getL()[0] / renderScale;
			yData[ j ] = ( pm.getP1().getL()[1] - maxCF ) / renderScale;
			++j;
		}

		final CurveFitter cfFullRes = new CurveFitter(xData, yData);
		cfFullRes.doFit( CurveFitter.EXP_RECOVERY );

//		System.out.println( cfFullRes.getFormula() );
//		System.out.println( cfFullRes.getFitGoodness() );
//		System.out.println( cfFullRes.getResultString() );
//		System.out.println( Util.printCoordinates( cfFullRes.getParams() ) );

		/*
		maxCF = -Double.MAX_VALUE;
		for ( int x = 0; x <= 823*4;++x)
			maxCF = Math.max( maxCF, cfFullRes.f(x) );
		System.out.println( "maxCF=" + maxCF );

		for ( int x = 0; x <= 823*4;++x)
			System.out.println( x+","+(cfFullRes.f(x)));
		*/

		final FitResult fitResult = new FitResult(cfFullRes, cfRenderScale, inliers.size());

		LOG.debug("getScalingFunction: exit, returning {}", fitResult);

		return fitResult;
	}

    private static Rectangle findRectangle(final ImageProcessor mask) {
        // TODO: assumes it is not rotated

        int minX = mask.getWidth();
        int maxX = 0;

        int minY = mask.getHeight();
        int maxY = 0;

        for (int y = 0; y < mask.getHeight(); ++y) {
            for (int x = 0; x < mask.getWidth(); ++x) {
                if (mask.getf(x, y) >= 255) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        LOG.debug("findRectangle: returning minX: {}, maxX: {}, minY: {}, maxY: {}", minX, maxX, minY, maxY);

        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnscaleTile.class);

	private static final JsonUtils.Helper<FitResult> FIT_RESULT_JSON_HELPER =
			new JsonUtils.Helper<>(FitResult.class);

}
