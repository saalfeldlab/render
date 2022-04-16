package org.janelia.render.client;

import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.parameters.CrossCorrelationParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.util.Util;

public class UnscaleSec19 {

	public static void main(final String[] args)
	{
		new ImageJ();

		CrossCorrelationParameters ccParameters = new CrossCorrelationParameters();
		ccParameters.fullScaleSampleSize = 250;
		ccParameters.fullScaleStepSize = 5;
		ccParameters.checkPeaks = 50;
		ccParameters.minResultThreshold = 0.5;
		ccParameters.subpixelAccuracy = true;
		final double renderScale = 0.25;

		// Adapted clipped render code from CrossCorrelationPointMatchClient below.
		// Minimally, must mount dm11 flyem at /groups/...
		// To make things faster (but not required), also mount nrs flyem to get access to mipmaps.

		final long maximumNumberOfCachedSourcePixels = 1_000_000_000; // 1GB
		final ImageProcessorCache sourceImageProcessorCache =
				new ImageProcessorCache(maximumNumberOfCachedSourcePixels,
										true,
										false);

		final String baseDataUrlString = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
		final String owner = "Z0720_07m_VNC";
		final String project = "Sec19";
		final String stack = "v1_acquire";
		final String generalTemplateString = baseDataUrlString + "/owner/" + owner + "/project/" + project +
											 "/stack/" + stack + "/tile/{id}/render-parameters";

		final FeatureRenderParameters featureRenderParameters = new FeatureRenderParameters();
		featureRenderParameters.renderScale = renderScale;
		featureRenderParameters.renderWithoutMask = false;

		final FeatureRenderClipParameters featureRenderClipParameters = new FeatureRenderClipParameters();
		featureRenderClipParameters.clipHeight = 500;
		featureRenderClipParameters.clipWidth = 500;

		final CanvasRenderParametersUrlTemplate urlTemplateForRun =
				CanvasRenderParametersUrlTemplate.getTemplateForRun(
						generalTemplateString,
						featureRenderParameters,
						featureRenderClipParameters);

		//layer 3781 of Sec19, tile 0 and 1
//		ImagePlus imp1 = new ImagePlus("/Users/preibischs/Documents/Janelia/Projects/Male CNS+VNC Alignment/07m/VNC-Sec 19/Merlin-6262_21-09-20_094008_0-0-0-InLens.png");
//		ImagePlus imp2 = new ImagePlus("/Users/preibischs/Documents/Janelia/Projects/Male CNS+VNC Alignment/07m/VNC-Sec 19/Merlin-6262_21-09-20_094008_0-0-1-InLens.png");
//		ImagePlus mask1 = new ImagePlus("/Users/preibischs/Documents/Janelia/Projects/Male CNS+VNC Alignment/07m/VNC-Sec 19/mask_7687x3500_left_100.tif");
//		ImagePlus mask2 = new ImagePlus("/Users/preibischs/Documents/Janelia/Projects/Male CNS+VNC Alignment/07m/VNC-Sec 19/mask_7687x3500_left_100.tif");

		final CanvasId pCanvasId = new CanvasId("3781.0",
												"21-09-20_094008_0-0-0.3781.0",
												MontageRelativePosition.LEFT);
		final CanvasId qCanvasId = new CanvasId("3781.0",
												"21-09-20_094008_0-0-1.3781.0",
												MontageRelativePosition.RIGHT);

		final CanvasIdWithRenderContext p = CanvasIdWithRenderContext.build(pCanvasId, urlTemplateForRun);
		final CanvasIdWithRenderContext q = CanvasIdWithRenderContext.build(qCanvasId, urlTemplateForRun);

		final TransformMeshMappingWithMasks.ImageProcessorWithMasks
				renderedPCanvas = CrossCorrelationPointMatchClient.renderCanvas(p, sourceImageProcessorCache);
		final TransformMeshMappingWithMasks.ImageProcessorWithMasks
				renderedQCanvas = CrossCorrelationPointMatchClient.renderCanvas(q, sourceImageProcessorCache);

		final ImagePlus imp1 = new ImagePlus(pCanvasId.getId(), renderedPCanvas.ip);
		final ImagePlus imp2 = new ImagePlus(qCanvasId.getId(), renderedQCanvas.ip);

		imp1.show();
		imp2.show();

		if (renderedPCanvas.mask != null) {
			final ImagePlus mask1 = new ImagePlus("p mask", renderedPCanvas.mask);
			mask1.show();
		}
		if (renderedQCanvas.mask != null) {
			final ImagePlus mask2 = new ImagePlus("q mask", renderedQCanvas.mask);
			mask2.show();
		}

		/*
		getCandidateMatches(imp1,
							renderedPCanvas.mask,
							imp2,
							renderedQCanvas.mask,
							true,
							renderScale,
							ccParameters );*/
	}

	private static List<PointMatch> getCandidateMatches(
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
				"getCandidateMatches: renderScale={}, minResultThreshold={}, scaledSampleSize={}, scaledStepSize={}, numTests={}, stepIncrement={}",
				renderScale, ccParameters.minResultThreshold, scaledSampleSize, scaledStepSize, numTests,
				stepIncrement);

		final List<PointMatch> candidates = new ArrayList<>();

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

				LOG.debug(minXOrY + " > " + maxXOrY + ", shift : " + Util.printCoordinates(result.getOffset())
						+ ", correlation (R)=" + result.getCrossCorrelation());

				final int stepDim = stepThroughY ? 1 : 0;
				final int otherDim = stepThroughY ? 0 : 1;
				final double r1XOrY = 0;
				final double center1XorY = minXOrY + scaledSampleSize / 2.0;

				final double r2XOrY = -result.getOffset(otherDim);
				final double center2XorY = center1XorY - result.getOffset(stepDim);

// just to place the points within the overlapping area
// (only matters for visualization)
				double shiftXOrY = 0;

				final int unmasked2XOrY = stepThroughY ? unmaskedArea2.x : unmaskedArea2.y;
				final int unmasked2WidthOrHeight = stepThroughY ? unmaskedArea2.width : unmaskedArea2.height;
				if (r2XOrY < unmasked2XOrY) {
					shiftXOrY += unmasked2XOrY - r2XOrY;
				} else if (r2XOrY >= unmasked2XOrY + unmasked2WidthOrHeight) {
					shiftXOrY -= r2XOrY - (unmasked2XOrY + unmasked2WidthOrHeight);
				}

				final Point p1, p2;
				if (stepThroughY) {
					p1 = new Point(new double[] { r1XOrY + shiftXOrY, center1XorY });
					p2 = new Point(new double[] { r2XOrY + shiftXOrY, center2XorY });
				} else {
					p1 = new Point(new double[] { center1XorY, r1XOrY + shiftXOrY });
					p2 = new Point(new double[] { center2XorY, r2XOrY + shiftXOrY });
				}

				candidates.add(new PointMatch(p1, p2));
			}
		}

		return candidates;
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

        LOG.debug("minX: {}, maxX: {}, minY: {}, maxY: {}", minX, maxX, minY, maxY);

        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnscaleSec19.class);

}
