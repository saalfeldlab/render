package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.process.ImageProcessor;
import net.imglib2.algorithm.localization.FitFunction;
import net.imglib2.algorithm.localization.FunctionFitter;
import net.imglib2.algorithm.localization.LevenbergMarquardtSolver;
import org.janelia.alignment.filter.ExponentialIntensityFilter;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	private static final ImageLoader.LoaderType LOADER_TYPE = ImageLoader.LoaderType.IMAGEJ_DEFAULT;
	private static final FunctionFitter FITTER = new LevenbergMarquardtSolver(1000, 1e-3, 1e-6);
	private static final FitFunction MODEL = new SigmoidalModel();

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();
		@Parameter(names = "--stack", description = "Name of source stack", required = true)
		public String stack;
		@Parameter(names = "--targetStack", description = "Name of target stack", required = true)
		public String targetStack;
		@Parameter(names = "--coefficientsFile", description = "File name for storing the estimated coefficients in the csv format; If not given, coefficients are not stored")
		private String coefficientsFile = null;
		@Parameter(names = "--averageOverLayer", description = "If true, average all estimated models and apply the average to the tiles (outliers are filtered)")
		public boolean averageOverLayer = false;
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
		final List<Double> zValues = renderClient.getStackZValues(params.stack);

		final StackMetaData stackMetaData = renderClient.getStackMetaData(params.stack);
		renderClient.setupDerivedStack(stackMetaData, params.targetStack);

		final PrintWriter writer = getWriterIfDesired();
		if (writer != null) {
			writer.println("tileId,a,b,c");
		}

		for (final double z : zValues) {
			final ResolvedTileSpecCollection tileSpecs = renderClient.getResolvedTiles(params.stack, z);
			final Map<String, double[]> coefficients = estimateCoefficients(tileSpecs);

			if (writer != null) {
				LOG.info("process: writing coefficients for z={} to file {}", z, params.coefficientsFile);
				appendCoefficients(coefficients, writer);
			}

			final Map<String, double[]> filterCoefficients = convertToMultiplicativeFactors(coefficients);
			applyExponentialCorrection(tileSpecs, filterCoefficients);

			renderClient.saveResolvedTiles(tileSpecs, params.targetStack, z);
		}

		if (writer != null) {
			writer.close();
		}
		
		if (params.completeTargetStack) {
			renderClient.setStackState(params.targetStack, StackMetaData.StackState.COMPLETE);
		}
	}

	private PrintWriter getWriterIfDesired() throws FileNotFoundException {
		if (params.coefficientsFile == null) {
			return null;
		}
		final File coefficientsFile = new File(params.coefficientsFile);
		if (coefficientsFile.exists()) {
			throw new IllegalArgumentException("process: coefficients file '" + params.coefficientsFile + "' already exists");
		}
		return new PrintWriter(coefficientsFile);
	}

	private static Map<String, double[]> estimateCoefficients(final ResolvedTileSpecCollection tileSpecs) {
		final TileSpec firstTileSpec = tileSpecs.getTileSpecs().stream().findFirst().orElseThrow();
		final int height = firstTileSpec.getHeight();
		final double[][] evaluationPoints = getPixelMidpoints(height);
		final double[] averages = new double[height];
		final Map<String, double[]> coefficients = new HashMap<>();

		for (final TileSpec tileSpec : tileSpecs.getTileSpecs()) {
			final ImageProcessor image = IMAGE_LOADER.get(tileSpec.getTileImageUrl(), 0, false, false, LOADER_TYPE, null);
			updateAverages(image, averages);

			final double[] parameters = new double[] {averages[0], 1, 0};
			try {
				FITTER.fit(evaluationPoints, averages, parameters, MODEL);
			} catch (final Exception e) {
				LOG.error("process: error fitting model", e);
			}

			coefficients.put(tileSpec.getTileId(), parameters);
		}
		return coefficients;
	}

	private static void updateAverages(final ImageProcessor image, final double[] average) {
		for (int y = 0; y < image.getHeight(); y++) {
			double sum = 0;
			for (int x = 0; x < image.getWidth(); x++) {
				sum += image.getf(x, y);
			}
			average[y] = sum / image.getWidth();
		}
	}

	private static double[][] getPixelMidpoints(final int n) {
		final double[][] pixels = new double[n][];
		for (int y = 0; y < n; y++) {
			pixels[y] = new double[] {y + 0.5};
		}
		return pixels;
	}

	private void appendCoefficients(final Map<String, double[]> coefficients, final PrintWriter writer) {
		for (final Map.Entry<String, double[]> entry : coefficients.entrySet()) {
			final String tileId = entry.getKey();
			final double[] coeff = entry.getValue();
			writer.println(tileId + "," + coeff[0] + "," + coeff[1] + "," + coeff[2]);
		}
	}

	private Map<String, double[]> convertToMultiplicativeFactors(final Map<String, double[]> coefficients) {
		final Map<String, double[]> filterCoefficients = new HashMap<>();
		final double[] average = computeAverageCoefficients(coefficients);

		for (final Map.Entry<String, double[]> entry : coefficients.entrySet()) {
			final String tileId = entry.getKey();
			final double[] coeff = entry.getValue();

			// first coefficient is the baseline intensity, which is discarded
			final double[] filterCoefficient = (average != null) ? average : new double[] {coeff[1], coeff[2]};
			filterCoefficients.put(tileId, filterCoefficient);
		}

		return filterCoefficients;
	}

	private double[] computeAverageCoefficients(final Map<String, double[]> coefficients) {
		if (! params.averageOverLayer) {
			return null;
		}

		LOG.info("computeAverageCoefficients: enter");
		final double[] average = new double[2];
		int count = 0;
		for (final Map.Entry<String, double[]> entry : coefficients.entrySet()) {
			final String tileId = entry.getKey();
			final double[] coeff = entry.getValue();
			
			if (isOutlier(coeff)) {
				LOG.debug("computeAverageCoefficients: skipping outlier tile {}", tileId);
				continue;
			}
			
			average[0] += coeff[1];
			average[1] += coeff[2];
			count++;
		}
		
		average[0] /= count;
		average[1] /= count;
		return average;
	}

	private static boolean isOutlier(final double[] c) {
		// c[0] = saturation value, c[1] = steepness, c[2] = center
		return c[0] > 255 || c[1] > 10.0 || Math.abs(c[2]) > 50.0;
	}

	private void applyExponentialCorrection(final ResolvedTileSpecCollection tileSpecs, final Map<String, double[]> filterCoefficients) {
		for (final Map.Entry<String, double[]> entry : filterCoefficients.entrySet()) {
			final String tileId = entry.getKey();
			final TileSpec tileSpec = tileSpecs.getTileSpec(tileId);
			final double[] coeff = entry.getValue();

			final FilterSpec filterSpec = FilterSpec.forFilter(new ExponentialIntensityFilter(coeff[0], coeff[1]));
			tileSpec.setFilterSpec(filterSpec);
		}
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
