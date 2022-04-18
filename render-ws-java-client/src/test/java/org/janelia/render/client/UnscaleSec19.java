package org.janelia.render.client;

import ij.ImageJ;
import ij.ImagePlus;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.UnscaleTile;
import org.janelia.alignment.match.parameters.CrossCorrelationParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.transform.CurveFitterTransform;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

public class UnscaleSec19 {

	public static void main(final String[] args)
	{
		new ImageJ();

		final CrossCorrelationParameters ccParameters = new CrossCorrelationParameters();
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

		/*
		// layer 12839, Merlin-6262_21-09-26_230432_0-0-0-InLens.png, Merlin-6262_21-09-26_230432_0-0-1-InLens.png
		CanvasId pCanvasId = new CanvasId("12839.0",
				"21-09-26_230432_0-0-0.12839.0",
				MontageRelativePosition.LEFT);
		CanvasId qCanvasId = new CanvasId("12839.0",
				"21-09-26_230432_0-0-1.12839.0",
				MontageRelativePosition.RIGHT);
		*/

		/*
		// layer 12840, Merlin-6262_21-09-26_230544_0-0-0-InLens.png, Merlin-6262_21-09-26_230544_0-0-1-InLens.png
		CanvasId pCanvasId = new CanvasId("12840.0",
				"21-09-26_230544_0-0-0.12840.0",
				MontageRelativePosition.LEFT);
		CanvasId qCanvasId = new CanvasId("12840.0",
				"21-09-26_230544_0-0-1.12840.0",
				MontageRelativePosition.RIGHT);
		*/

		/*
		// layer 12841, Merlin-6262_21-09-26_230647_0-0-0-InLens.png, Merlin-6262_21-09-26_230647_0-0-1-InLens.png
		CanvasId pCanvasId = new CanvasId("12841.0",
				"21-09-26_230647_0-0-0.12841.0",
				MontageRelativePosition.LEFT);
		CanvasId qCanvasId = new CanvasId("12841.0",
				"21-09-26_230647_0-0-1.12841.0",
				MontageRelativePosition.RIGHT);
		*/

		/*
		// layer 12842, Merlin-6262_21-09-26_230749_0-0-0-InLens.png, Merlin-6262_21-09-26_230749_0-0-1-InLens.png
		CanvasId pCanvasId = new CanvasId("12842.0",
				"21-09-26_230749_0-0-0.12842.0",
				MontageRelativePosition.LEFT);
		CanvasId qCanvasId = new CanvasId("12842.0",
				"21-09-26_230749_0-0-1.12842.0",
				MontageRelativePosition.RIGHT);
		*/

		//layer 3781 of Sec19, tile 0 and 1
		/*
		final CanvasId pCanvasId = new CanvasId("3781.0",
												"21-09-20_094008_0-0-0.3781.0",
												MontageRelativePosition.LEFT);
		final CanvasId qCanvasId = new CanvasId("3781.0",
												"21-09-20_094008_0-0-1.3781.0",
												MontageRelativePosition.RIGHT);
		*/

		/*
		//"3785.0", Merlin-6262_21-09-20_094420_0-0-0-InLens.png, Merlin-6262_21-09-20_094420_0-0-1-InLens.png
		CanvasId pCanvasId = new CanvasId("3785.0",
				"21-09-20_094420_0-0-0.3785.0",
				MontageRelativePosition.LEFT);
		CanvasId qCanvasId = new CanvasId("3785.0",
				"21-09-20_094420_0-0-1.3785.0",
				MontageRelativePosition.RIGHT);
		*/

		/*
		// 3786, Merlin-6262_21-09-20_094523_0-0-0-InLens.png, Merlin-6262_21-09-20_094523_0-0-1-InLens.png
		CanvasId pCanvasId = new CanvasId("3786.0",
				"21-09-20_094523_0-0-0.3786.0",
				MontageRelativePosition.LEFT);
		CanvasId qCanvasId = new CanvasId("3786.0",
				"21-09-20_094523_0-0-1.3786.0",
				MontageRelativePosition.RIGHT);
		*/

		// 8783.0, Merlin-6262_21-09-24_002136_0-0-0-InLens.png, Merlin-6262_21-09-24_002136_0-0-1-InLens.png
		CanvasId pCanvasId = new CanvasId("8783.0",
				"21-09-24_002136_0-0-0.8783.0",
				MontageRelativePosition.LEFT);
		CanvasId qCanvasId = new CanvasId("8783.0",
				"21-09-24_002136_0-0-1.8783.0",
				MontageRelativePosition.RIGHT);

		/*
		// (CORRECT) 13240, Merlin-6262_21-09-27_060232_0-0-0-InLens.png, Merlin-6262_21-09-27_060232_0-0-1-InLens.png
		CanvasId pCanvasId = new CanvasId("13240.0",
				"21-09-27_060232_0-0-0.13240.0",
				MontageRelativePosition.LEFT);
		CanvasId qCanvasId = new CanvasId("13240.0",
				"21-09-27_060232_0-0-1.13240.0",
				MontageRelativePosition.RIGHT);
		*/

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

		/*
		if (renderedPCanvas.mask != null) {
			final ImagePlus mask1 = new ImagePlus("p mask", renderedPCanvas.mask);
			mask1.show();
		}
		if (renderedQCanvas.mask != null) {
			final ImagePlus mask2 = new ImagePlus("q mask", renderedQCanvas.mask);
			mask2.show();
		}*/

		//
		// compute the function and statistics
		//
		final UnscaleTile.FitResult fr = UnscaleTile.getScalingFunction(
				imp1,
				renderedPCanvas.mask,
				imp2,
				renderedQCanvas.mask,
				true,
				renderScale,
				ccParameters);

		//
		// render the result
		//
		final CurveFitterTransform t = new CurveFitterTransform(fr.getCfRenderScale(), 1);

		final RandomAccessibleInterval<FloatType> img = Converters.convertRAI(ImageJFunctions.wrapByte( imp1 ), (i,o) -> o.setReal( i.get() ), new FloatType());
		final RandomAccessibleInterval<FloatType> tImg = ArrayImgs.floats( img.dimensionsAsLongArray() );

		final RealRandomAccessible<FloatType> interpolated = Views.interpolate(Views.extendZero(img), new NLinearInterpolatorFactory<>());

		final Cursor<FloatType> c = Views.iterable( tImg ).localizingCursor();
		final RealRandomAccess<FloatType> rra = interpolated.realRandomAccess();
		final double[] loc = new double[ 2 ];

		while ( c.hasNext() )
		{
			c.fwd();
			c.localize( loc );
			t.applyInPlace(loc);
			rra.setPosition(loc);
			c.get().set( rra.get());
		}

		ImageJFunctions.show(tImg);
	}

    private static final Logger LOG = LoggerFactory.getLogger(UnscaleSec19.class);

}
