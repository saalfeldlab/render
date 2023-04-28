package org.janelia.render.client.intensityadjust.virtual;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.FloatImagePlus;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.real.FloatType;
import org.janelia.alignment.intensity.QuadraticIntensityMap;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;
import org.janelia.render.client.solver.visualize.VisualizeTools;


public class QuadraticOnTheFlyIntensity extends OnTheFlyIntensity {


	public QuadraticOnTheFlyIntensity(
			final MinimalTileSpecWrapper p,
			final double[][] abc_coefficients,
			final int numCoefficients) {
		super(p, abc_coefficients, numCoefficients);
	}

	@Override
	public FloatProcessor computeIntensityCorrectionOnTheFly(
			final MinimalTileSpecWrapper p,
			final double[][] abc_coefficients,
			final int numCoefficients,
			final ImageProcessorCache imageProcessorCache) {

		final FloatProcessor as = new FloatProcessor(numCoefficients, numCoefficients);
		final FloatProcessor bs = new FloatProcessor(numCoefficients, numCoefficients);
		final FloatProcessor cs = new FloatProcessor(numCoefficients, numCoefficients);

		final ImageProcessorWithMasks imp = VisualizeTools.getUntransformedProcessorWithMasks(p.getTileSpec(), imageProcessorCache);

		final FloatProcessor fp = imp.ip.convertToFloatProcessor();
		fp.resetMinAndMax();
		final double min = 0;
		final double max = 255;
		System.out.println(min + ", " + max);
		final double delta = max - min;

		for (int i = 0; i < numCoefficients * numCoefficients; ++i) {
			final double[] abc = abc_coefficients[i];

			// mapping coefficients of polynomial on [0, 1] x [0, 1]
			// to coefficients of polynomial of the same shape on [min, max] x [min, max]
			final double anew = abc[0] / delta;
			as.setf(i, (float) anew);
			bs.setf(i, (float) (min * anew * (min / delta - 2) + abc[1]));
			cs.setf(i, (float) (min * (min * anew - abc[1]) + delta * abc[2] + min));
		}
		final ImageStack coefficientsStack = new ImageStack(numCoefficients, numCoefficients);
		coefficientsStack.addSlice(as);
		coefficientsStack.addSlice(bs);
		coefficientsStack.addSlice(cs);

		@SuppressWarnings({"rawtypes", "unchecked"})
		final QuadraticIntensityMap<FloatType> map =
				new QuadraticIntensityMap<>((FloatImagePlus) ImagePlusImgs.from(new ImagePlus("", coefficientsStack)));

		final long[] dims = new long[]{imp.getWidth(), imp.getHeight()};
		final Img<FloatType> img = ArrayImgs.floats((float[])fp.getPixels(), dims);

		map.run(img);

		return fp;
	}
}

