package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Measurements;
import ij.plugin.ImageCalculator;
import ij.plugin.Scaler;
import ij.plugin.filter.RankFilters;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import net.imagej.ImageJ;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.neighborhood.HyperSphereShape;
import net.imglib2.img.Img;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Create and store a background image that can be used to subtract a
 * spatially varying background from a set of images.
 * Originally developed for the CellMap jrc_mus_thymus_1 dataset.
 *
 * @author Michael Innerberger
 */
public class BackgroundCorrectionClient {

	private static final ImageJ ij = new ImageJ();

	private final Parameters params;
	private final RenderDataClient renderClient;

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@ParametersDelegate
		private final ZRangeParameters zRangeParams = new ZRangeParameters();
		@Parameter(names = "--stack", description = "Stack for which to correct background", required = true)
		private String stack;
		@Parameter(names = "--regex", description = "Regular expression for matching tiles to correct background for; all tiles are corrected if not given")
		private String regex = null;
		@Parameter(names = "--radius", description = "Radius for median filter in pixels (default: 50.0)")
		private double radius = 50.0;
		@Parameter(names = "--scale", description = "Scale to use for median filter (default: 0.1)")
		private double scale = 0.1;
		@Parameter(names = "--outputFolder", description = "Folder to write corrected images to (default: ./background_corrected)")
		private String outputFolder = "background_corrected";
	}

	public static void main(String[] args) {

		if (args.length == 0) {
			args = new String[] {
					"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
					"--owner", "cellmap",
					"--project", "jrc_mus_thymus_1",
					"--stack", "v2_acquire_align",
					"--minZ", "1250",
					"--maxZ", "1250",
					"--regex", ".*_0-[01]-1.*",
					"--radius", "700.0",
					"--scale", "0.05",
			};
		}

		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final BackgroundCorrectionClient client = new BackgroundCorrectionClient(parameters);
				client.correctBackground();
			}
		};
		clientRunner.run();
	}

	public BackgroundCorrectionClient(final Parameters parameters) {
		this.params = parameters;
		this.renderClient = new RenderDataClient(parameters.renderParams.baseDataUrl, parameters.renderParams.owner, parameters.renderParams.project);
	}

	public void correctBackground() {
		final ResolvedTileSpecCollection tileSpecs = getTileSpecs();
		final ImageProcessorCache imageProcessorCache = ImageProcessorCache.DISABLED_CACHE;

		ensureOutputFolderExists();
		for (final TileSpec tileSpec : tileSpecs.getTileSpecs()) {
			final ImageProcessor ip = loadImage(tileSpec, imageProcessorCache);

			final long start = System.currentTimeMillis();
//			final ImageProcessor processedImage = subtractBackground(ip);
			final ImageProcessor processedImage = subtractBackgroundMirrorOob(ip);
//			final ImageProcessor processedImage = subtractBackgroundIL2(ip);
			final long end = System.currentTimeMillis();
			LOG.info("Corrected background for tile {} in {} ms", tileSpec.getTileId(), end - start);

			saveImage(processedImage, tileSpec);
		}
	}

	private ResolvedTileSpecCollection getTileSpecs() {
		ResolvedTileSpecCollection tileSpecs = null;
		try {
			tileSpecs = renderClient.getResolvedTiles(params.stack,
													  params.zRangeParams.minZ,
													  params.zRangeParams.maxZ,
													  null,
													  null,
													  null,
													  null,
													  null,
													  params.regex);
		} catch (final IOException e) {
			LOG.error("Could not get tile specs: ", e);
			System.exit(1);
		}
		return tileSpecs;
	}

	private void ensureOutputFolderExists() {
		final Path outputFolder = Path.of(params.outputFolder);
		final boolean folderExists = outputFolder.toFile().exists() || outputFolder.toFile().mkdirs();

		if (!folderExists) {
			LOG.error("Could not create output folder: {}", outputFolder);
			System.exit(1);
		}
	}

	private ImageProcessor loadImage(final TileSpec tileSpec, final ImageProcessorCache imageProcessorCache) {
		final ChannelSpec firstChannelSpec = tileSpec.getAllChannels().get(0);
		final String tileId = tileSpec.getTileId();
		final ImageAndMask imageAndMask = firstChannelSpec.getFirstMipmapImageAndMask(tileId);
		return imageProcessorCache.get(imageAndMask.getImageUrl(),
									   0,
									   false,
									   firstChannelSpec.is16Bit(),
									   imageAndMask.getImageLoaderType(),
									   imageAndMask.getImageSliceNumber());
	}

	private ImageProcessor subtractBackground(final ImageProcessor ip) {
		// convert to 32-bit grayscale (float) for lossless processing
		final ImagePlus original = new ImagePlus("original", ip);
		final ImageConverter imageConverter = new ImageConverter(original);
		imageConverter.convertToGray32();

		// resize to speed up processing
		final int targetWidth = (int) (params.scale * ip.getWidth());
		final int targetHeight = (int) (params.scale * ip.getHeight());
		final ImagePlus background = Scaler.resize(original, targetWidth, targetHeight, 1, "bilinear");

		// median filtering for actual background computation
		final double downscaledRadius = params.radius * params.scale;
		final RankFilters rankFilters = new RankFilters();
		rankFilters.rank(background.getProcessor(), downscaledRadius, RankFilters.MEDIAN);

		// subtract mean to not shift the actual image values
		final double mean = ImageStatistics.getStatistics(background.getProcessor(), Measurements.MEAN, null).mean;
		background.getProcessor().subtract(mean);

		// finally, subtract the background
		final ImagePlus resizedBackground = Scaler.resize(background, ip.getWidth(), ip.getHeight(), 1, "bilinear");
		ImageCalculator.run(original, resizedBackground, "subtract");

		// convert back to original bit depth
		imageConverter.convertToGray8();
		return original.getProcessor();
	}

	private ImageProcessor subtractBackgroundMirrorOob(final ImageProcessor ip) {
		// convert to 32-bit grayscale (float) for lossless processing
		final ImagePlus original = new ImagePlus("original", ip);
		final ImageConverter imageConverter = new ImageConverter(original);
		imageConverter.convertToGray32();

		// resize to speed up processing
		final int targetWidth = (int) (params.scale * ip.getWidth());
		final int targetHeight = (int) (params.scale * ip.getHeight());
		final ImagePlus background = Scaler.resize(original, targetWidth, targetHeight, 1, "bilinear");

		// median filtering for actual background computation
		final double downscaledRadius = params.radius * params.scale;
		final RankFilters rankFilters = new RankFilters();
		final ImagePlus extendedBackground = extendBorder(background, downscaledRadius);
		rankFilters.rank(extendedBackground.getProcessor(), downscaledRadius, RankFilters.MEDIAN);
		final ImagePlus filteredBackground = crop(extendedBackground, downscaledRadius);

		// subtract mean to not shift the actual image values
		final double mean = ImageStatistics.getStatistics(filteredBackground.getProcessor(), Measurements.MEAN, null).mean;
		filteredBackground.getProcessor().subtract(mean);

		// finally, subtract the background
		final ImagePlus resizedBackground = Scaler.resize(filteredBackground, ip.getWidth(), ip.getHeight(), 1, "bilinear");
		ImageCalculator.run(original, resizedBackground, "subtract");

		// convert back to original bit depth
		imageConverter.convertToGray8();
		return original.getProcessor();
	}

	private ImageProcessor subtractBackgroundIL2(final ImageProcessor ip) {
		// convert to 32-bit grayscale (float) for lossless processing
		final Img<FloatType> original = ImageJFunctions.convertFloat(new ImagePlus("original", ip));

		// resize to speed up processing
		final int targetWidth = (int) (params.scale * ip.getWidth());
		final int targetHeight = (int) (params.scale * ip.getHeight());
		Img<FloatType> background = rescale(original, new long[] {targetWidth, targetHeight});

		// median filtering for actual background computation
		final double downscaledRadius = params.radius * params.scale;
		background = medianFilter(background, downscaledRadius);

		// subtract mean to not shift the actual image values
		subtractAverage(background);

		// finally, subtract the background
		final Img<FloatType> resizedBackground = rescale(background, original.dimensionsAsLongArray());
		LoopBuilder.setImages(original, resizedBackground).forEachPixel((o, b) -> o.set(o.get() - b.get()));

		// convert back to original bit depth
		return ImageJFunctions.wrap(convertToByte(original), "corrected").getProcessor();
	}

	private void saveImage(final ImageProcessor ip, final TileSpec tileSpec) {
		final String tileId = tileSpec.getTileId();
		final ImagePlus imp = new ImagePlus(tileId, ip);
		final Path targetPath = Path.of(params.outputFolder, tileId + ".png").toAbsolutePath();
		IJ.save(imp, targetPath.toString());
	}

	private Img<FloatType> rescale(
			final Img<FloatType> input,
			final long[] newDims)
	{
		final int n = input.numDimensions();
		final double[] scaleFactors = new double[n];
		for (int i = 0; i < n; i++)
			scaleFactors[i] = (double) newDims[i] / input.dimension(i);

		final InterpolatorFactory<FloatType, RandomAccessible<FloatType>> interpolator = new NLinearInterpolatorFactory<>();
		final RandomAccessibleInterval<FloatType> scaledView = ij.op().transform().scaleView(input, scaleFactors, interpolator);

		final Img<FloatType> output = input.factory().create(newDims);
		LoopBuilder.setImages(scaledView, output).forEachPixel((v, o) -> o.set(v));
		return output;
	}

	private Img<FloatType> medianFilter(
			final Img<FloatType> input,
			final double radius)
	{
		final long integerRadius = Math.round(radius);

		final Img<FloatType> output = input.factory().create(input);
		final IterableInterval<FloatType> out = Views.iterable(output);
		ij.op().filter().median(out, input, new HyperSphereShape(integerRadius));

		return output;
	}

	private Img<UnsignedByteType> convertToByte(final Img<FloatType> input) {
		return ij.op().convert().uint8(input);
	}

	private void subtractAverage(final Img<FloatType> input) {
		final float mean = ij.op().stats().mean(input).getRealFloat();
		LoopBuilder.setImages(input).forEachPixel(p -> p.set(p.get() - mean));
	}

	private ImagePlus extendBorder(final ImagePlus input, final double padding) {
		final Img<FloatType> in = ImageJFunctions.wrap(input);
		final long extendSize = (long) Math.ceil(padding);
		final IntervalView<FloatType> view = Views.expandMirrorSingle(in, extendSize, extendSize);

		// make copy, otherwise the changes of the median filter are not visible
		final ImagePlusImg<FloatType, FloatArray> test = ImagePlusImgs.floats(input.getWidth() + 2 * extendSize, input.getHeight() + 2 * extendSize);
		LoopBuilder.setImages(view, test).forEachPixel((v, t) -> t.set(v.get()));

		final ImagePlus out = test.getImagePlus();
		out.getProcessor().setMinAndMax(0.0, 255.0);
		return out;
	}

	private ImagePlus crop(final ImagePlus input, final double padding) {
		final long cropSize = (long) Math.ceil(padding);
		final long[] min = new long[] {cropSize, cropSize};
		final long[] max = new long[] {input.getWidth() - cropSize - 1, input.getHeight() - cropSize - 1};
		final Img<FloatType> in = ImageJFunctions.wrap(input);

		final RandomAccessibleInterval<FloatType> roi = Views.interval(in, min, max);
		final ImagePlus out = ImageJFunctions.wrap(roi, "cropped");
		out.getProcessor().setMinAndMax(0.0, 255.0);
		return out;
	}

	private static final Logger LOG = LoggerFactory.getLogger(BackgroundCorrectionClient.class);
}
