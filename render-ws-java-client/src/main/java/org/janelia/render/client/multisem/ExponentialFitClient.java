package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.process.ImageProcessor;
import net.imglib2.algorithm.localization.FitFunction;
import net.imglib2.algorithm.localization.FunctionFitter;
import net.imglib2.algorithm.localization.LevenbergMarquardtSolver;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

/**
 * Fits an exponential model in y to the image data of the given stack. This should correct an exponential
 * intensity drop towards the upper edge of the images.
 * <p>
 * The model is of the form y = a / (1 + exp(-b * (x - c))), where a, b, and c are the parameters to be estimated;
 * this is a 3-parameter sigmoidal model. The parameter a describes the baseline intensity and is discarded in the
 * final model, so that the correction can be done by multiplying with (1 + exp(-b * (x - c))) in the y-direction.
 *
 * @author Michael Innerberger
 */
public class ExponentialFitClient {

	private static final ImageProcessorCache IMAGE_LOADER = ImageProcessorCache.DISABLED_CACHE;

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();
		@Parameter(names = "--stack", description = "Name of source stack", required = true)
		public String stack;
		@Parameter(names = "--targetStack", description = "Name of target stack", required = true)
		public String targetStack;
		@Parameter(names = "--coefficientsFile", description = "File name for storing the estimated coefficients in the csv format; If not given, coefficients are not stored")
		private String coefficientsFile = null;
		@Parameter(names = "--averageOverLayer", description = "If true, average all estimated models and apply the average to the tiles", arity = 0)
		public boolean averageOverLayer = true;
		@Parameter(names = "--completeTargetStack", description = "Complete the target stack after fitting", arity = 0)
		public boolean completeTargetStack = false;
	}


	private final Parameters params;

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws IOException {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final ExponentialFitClient client = new ExponentialFitClient(parameters);
				client.process();
			}
		};
		clientRunner.run();
	}

	public ExponentialFitClient(final Parameters parameters) {
		this.params = parameters;
	}

	public void process() throws IOException {
		LOG.info("process: entry");
		final RenderDataClient renderClient = params.renderWeb.getDataClient();
		final ResolvedTileSpecCollection tileSpecs = renderClient.getResolvedTiles(params.stack, 2.0);
		final ImageLoader.LoaderType loaderType = ImageLoader.LoaderType.IMAGEJ_DEFAULT;

		final TileSpec firstTileSpec = tileSpecs.getTileSpecs().stream().findFirst().orElseThrow();
		final int height = firstTileSpec.getHeight();
		final double[][] pixels = new double[height][];
		for (int y = 0; y < height; y++) {
			pixels[y] = new double[] { y };
		}

		final TileSpec tileSpec = tileSpecs.getTileSpecs().stream().findFirst().orElseThrow();
		final ImageProcessor image = IMAGE_LOADER.get(tileSpec.getTileImageUrl(), 0, false, false, loaderType, null);
		final double[] average = new double[image.getHeight()];
		for (int y = 0; y < image.getHeight(); y++) {
			double sum = 0;
			for (int x = 0; x < image.getWidth(); x++) {
				sum += image.getf(x, y);
			}
			average[y] = sum / image.getHeight();
			pixels[y] = new double[] { y };
		}
		System.out.println("average = " + Arrays.toString(average));

		final FunctionFitter fitter = new LevenbergMarquardtSolver();
		final FitFunction model = new SigmoidalModel();
		final double[] parameters = new double[]{average[0], 1, 0};

		try {
			fitter.fit(pixels, average, parameters, model);
		} catch (final Exception e) {
			LOG.error("process: error fitting model", e);
		}

		System.out.println("fitted parameters = " + Arrays.toString(parameters));
	}

	/**
	 * Sigmoidal model of the form y = a / (1 + exp(-b * (x - c))).
	 */
	private static class SigmoidalModel implements FitFunction {

		@Override
		public double val(final double[] x, final double[] a) {
			return a[0] / (1 + Math.exp(-a[1] * (x[0] - a[2])));
		}

		@Override
		public double grad(final double[] x, final double[] a, final int i) {
			final double z = Math.exp(-a[1] * (x[0] - a[2]));
			final double z1 = 1 + z;
			switch (i) {
				case 0:
					return 1 / z1;
				case 1:
					return a[0] * (x[0] - a[2]) * z / (z1 * z1);
				case 2:
					return - a[0] * a[1] * z / (z1 * z1);
				default:
					throw new IllegalArgumentException("Invalid parameter index: " + i);
			}
		}

		@Override
		public double hessian(final double[] x, final double[] a, final int i, final int j) {
			throw new UnsupportedOperationException("Hessian not implemented for sigmoidal model");
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(ExponentialFitClient.class);
}
