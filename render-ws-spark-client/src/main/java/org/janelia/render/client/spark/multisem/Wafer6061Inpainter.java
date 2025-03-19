package org.janelia.render.client.spark.multisem;


import com.beust.jcommander.Parameter;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.util.Grid;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


/**
 * Class for inpainting small gaps between tiles in the wafer 60/61 dataset.
 * <p>
 * The regions to inpaint are determined by looking at mask pixels: if an unmasked pixel is encountered,
 * the algorithm looks at pairs of pixels `stepSize` away in the x and y directions to find non-masked pixels.
 * If such a pair is found, the pixel is most likely in a narrow gap between tiles and needs inpainting. The
 * inpainting is done by averaging the image data from the adjacent pixels in the z direction. If only one of the
 * image values in z is available, that value is used. If neither is available, the pixel is set to 0.
 */
public class Wafer6061Inpainter {

	private static final Logger LOG = LoggerFactory.getLogger(Wafer6061Inpainter.class);

	public static class Parameters extends CommandLineParameters {
		@Parameter(
				names = "--n5Path",
				description = "Path to the N5 container containing the data and the mask.",
				required = true)
		public String n5Path;

		@Parameter(
				names = "--dataset",
				description = "Name of the dataset to inpaint; assumed to be a multiscale pyramid, only s0 is inpainted.",
				required = true)
		public String dataset;

		@Parameter(
				names = "--mask",
				description = "Name of the mask dataset. This is supposed be a binary uint8 mask covering the whole dataset.",
				required = true)
		public String mask;

		@Parameter(
				names = "--output",
				description = "Name of the dataset to write the inpainted data to. Only blocks that are inpainted are written. "
						+ "If omitted, input blocks are overwritten.")
		public String output;

		@Parameter(
				names = "--inpaintingSize",
				description = "Rough size of the inpainting region in pixels. This is used to determine the regions to inpaint, so better to be too large than too small.",
				required = true)
		public int stepSize;


		public void validate() {
			if (stepSize <= 0) {
				throw new IllegalArgumentException("Inpainting size must be positive");
			}
		}

		public String fullDataset() {
			return dataset + "/s0";
		}
	}

	private final Parameters param;

	private ExtendedAttributes tissueAttributes;
	private ExtendedAttributes maskAttributes;


	public Wafer6061Inpainter(final Parameters parameters) {
		this.param = parameters;
	}

	public void run() {
		final String output = param.output == null ? "input dataset" : "'" + param.output + "'";
		LOG.info("Inpainting dataset '{}' in '{}' using mask '{}' and writing to {}",
				 param.dataset, param.n5Path, param.mask, output);

		// Read and cache some metadata of the tissue and mask datasets
		// Assume that the tissue is a multiscale pyramid / mask is a standalone dataset
		try (final N5Reader n5 = new N5Factory().openReader(N5Factory.StorageFormat.N5, param.n5Path)) {
			LOG.info("Reading metadata from {}", param.n5Path);
			tissueAttributes = ExtendedAttributes.read(n5, param.fullDataset(), param.dataset);
			maskAttributes = ExtendedAttributes.read(n5, param.mask, param.mask);

			if (param.output == null) {
				param.output = param.fullDataset();
				LOG.info("Output dataset equals input dataset. Overwriting blocks in the input dataset '{}'", param.output);
			} else if (n5.exists(param.output)) {
				throw new IllegalArgumentException("Dataset '" + param.output + "' is different from the input dataset and already exists. Stopping.");
			} else {
				LOG.info("Output dataset is '{}'. Creating new dataset.", param.output);
				try (final N5Writer n5Writer = new N5Factory().openWriter(N5Factory.StorageFormat.N5, param.n5Path)) {
					n5Writer.createDataset(param.output, tissueAttributes.attrs);
				}
			}
		}

		final SparkConf conf = new SparkConf().setAppName("Wafer6061Inpainter");
		try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
			runWithSparkContext(sparkContext);
		}
	}

	private void runWithSparkContext(final JavaSparkContext sparkContext) {
		// Find out which blocks need inpainting (i.e., find blocks that are neither all mask or all void)
		final List<Grid.Block> maskBlocks = Grid.create(maskAttributes.attrs.getDimensions(), maskAttributes.attrs.getBlockSize());
		final JavaRDD<Grid.Block> maskRDD = sparkContext.parallelize(maskBlocks);
		final Broadcast<ExtendedAttributes> maskAttributesBroadcast = sparkContext.broadcast(maskAttributes);
		final Broadcast<Parameters> paramBroadcast = sparkContext.broadcast(param);
		LOG.info("Filtering empty mask blocks from {} blocks", maskBlocks.size());

		final List<Grid.Block> nonHomogeneousMaskBlocks = maskRDD
				.map(block -> translateAndCheckHomogeneity(block,
														   maskAttributesBroadcast.value().min,
														   paramBroadcast.value()))
				.filter(Objects::nonNull)
				.collect();
		LOG.info("Found {} non-homogeneous mask blocks", nonHomogeneousMaskBlocks.size());

		// Check which tissue blocks are covered by the potentially inpainted mask blocks determined above
		final List<Grid.Block> tissueBlocks = Grid.create(tissueAttributes.attrs.getDimensions(), tissueAttributes.attrs.getBlockSize());
		final JavaRDD<Grid.Block> tissueBlocksRDD = sparkContext.parallelize(tissueBlocks);
		final Broadcast<ExtendedAttributes> tissueAttributesBroadcast = sparkContext.broadcast(tissueAttributes);
		final Broadcast<List<Grid.Block>> maskBlocksBroadcast = sparkContext.broadcast(nonHomogeneousMaskBlocks);

		final List<Grid.Block> tissueBlocksToInpaint = tissueBlocksRDD.map(
						block -> translateAndCheckIfOverlaps(block,
															 tissueAttributesBroadcast.value().min,
															 maskBlocksBroadcast.value()))
				.filter(Objects::nonNull)
				.collect();
		LOG.info("Found {} tissue blocks to inpaint", tissueBlocksToInpaint.size());

		// Inpaint the blocks
		final JavaRDD<Grid.Block> inpaintingBlocksRDD = sparkContext.parallelize(tissueBlocksToInpaint);

		inpaintingBlocksRDD.foreach(block -> {
		});
	}

	private static Grid.Block translateAndCheckHomogeneity(
			final Grid.Block block,
			final long[] shift,
			final Parameters param
	) {
		LogUtilities.setupExecutorLog4j("");

		// Translate the block to physical coordinates
		final Interval blockInterval = Intervals.translate(block, shift);
		final Grid.Block translatedBlock = new Grid.Block(blockInterval, block.gridPosition);

		// Read the mask block and check if it is homogeneous
		boolean isHomogeneous = true;
		try (final N5Reader n5 = new N5Factory().openReader(N5Factory.StorageFormat.N5, param.n5Path)) {
			final Img<UnsignedByteType> mask = N5Utils.open(n5, param.mask);
			final RandomAccessibleInterval<UnsignedByteType> maskPixels = Views.interval(mask, translatedBlock);

			final UnsignedByteType firstPixel = maskPixels.firstElement();
			for (final UnsignedByteType pixel : maskPixels) {
				if (! pixel.equals(firstPixel)) {
					isHomogeneous = false;
					break;
				}
			}
		}

		final String blockType = isHomogeneous ? "homogeneous -> skip" : "non-homogeneous -> possibly inpaint";
		LOG.info("Mask block {} at {} is {}", block.gridPosition, blockInterval.minAsLongArray(), blockType);
		return isHomogeneous ? null : translatedBlock;
	}

	private static Grid.Block translateAndCheckIfOverlaps(
			final Grid.Block block,
			final long[] shift,
			final List<Grid.Block> blocksToCheckAgainst
	) {
		LogUtilities.setupExecutorLog4j("");

		// Translate the block to physical coordinates
		final Interval blockInterval = Intervals.translate(block, shift);
		final Grid.Block translatedBlock = new Grid.Block(blockInterval, block.gridPosition);

		// Check if the block overlaps with any of the mask blocks that might need inpainting
		for (final Interval maskBlock : blocksToCheckAgainst) {
			final boolean intervalsAreDisjoint = Intervals.isEmpty(Intervals.intersect(translatedBlock, maskBlock));
			if (! intervalsAreDisjoint) {
				LOG.info("Tissue block {} at {} is determined a candidate for inpainting",
						 translatedBlock.gridPosition, translatedBlock.minAsLongArray());
				return block;
			}
		}

		LOG.info("Tissue block {} at {} is not a candidate for inpainting",
				 translatedBlock.gridPosition, translatedBlock.minAsLongArray());
		return null;
	}

	private void inpaintBlock(
			final Grid.Block block,
			final long[] maskMin,
			final Parameters param,
			final DatasetAttributes targetAttributes
	) {
		LogUtilities.setupExecutorLog4j("Block " + Arrays.toString(block.gridPosition));

		// Preallocate the inpainted block
		final Img<UnsignedByteType> inpaintedBlock = ArrayImgs.unsignedBytes(block.dimensions);

		try (final N5Reader n5 = new N5Factory().openReader(N5Factory.StorageFormat.N5, param.n5Path)) {
			// Load and translate the tissue and mask data
			LOG.info("Loading data at {}", block.offset);
			final Img<UnsignedByteType> rawTissue = N5Utils.open(n5, param.fullDataset());
			final Img<UnsignedByteType> rawMask = N5Utils.open(n5, param.mask);

			final RandomAccessibleInterval<UnsignedByteType> tissue = Views.translate(rawTissue, block.offset);
			final RandomAccessible<UnsignedByteType> mask = Views.translate(Views.extendValue(rawMask, 0.0f), maskMin);

			// For each pixel, determine if it should be inpainted and if so, inpaint it by interpolating in z
			LOG.info("Start inpainting");
			final long start = System.currentTimeMillis();
			final Cursor<UnsignedByteType> targetCursor = Views.translate(inpaintedBlock, block.offset).localizingCursor();
			final long[] location = new long[3];
			final PixelFiller interpolator = new PixelFiller(Views.interval(tissue, block), Views.interval(mask, block), param.stepSize);

			while (targetCursor.hasNext()) {
				final UnsignedByteType targetPixel = targetCursor.next();
				targetCursor.localize(location);
				final int value = interpolator.getPixel(location);
				targetPixel.set(value);
			}
			LOG.info("Finished inpainting in {} ms", System.currentTimeMillis() - start);
		}

		try (final N5Writer n5Writer = new N5Factory().openWriter(N5Factory.StorageFormat.N5, param.n5Path)) {
			N5Utils.saveBlock(inpaintedBlock, n5Writer, param.output, targetAttributes, block.gridPosition);
			LOG.info("Wrote tissue block to '{}'", param.output);
		} catch (final Exception e) {
			LOG.error("Failed to write inpainted block", e);
		}
	}


	public static void main(final String[] args) {
		final String[] testArgs = {
				"--n5Path", "/Users/innerbergerm/Data/render-exports/wafer60.n5",
				"--dataset", "tissue",
				"--mask", "mask",
//				"--output", "inpainted",
				"--inpaintingSize", "20"
		};

		final ClientRunner clientRunner = new ClientRunner(testArgs) {
			@Override
			public void runClient(final String[] args) {

				final Wafer6061Inpainter.Parameters parameters = new Wafer6061Inpainter.Parameters();
				parameters.parse(args);
				parameters.validate();

				LOG.info("runClient: entry, parameters={}", parameters);

				final Wafer6061Inpainter inpainter = new Wafer6061Inpainter(parameters);
				inpainter.run();
			}
		};
		clientRunner.run();
	}


	/**
	 * Performs all the inpainting-logic, i.e., when and how to inpaint.
	 */
	private static class PixelFiller {

		private final RandomAccess<UnsignedByteType> tissueAccess;
		private final RandomAccess<UnsignedByteType> maskAccess;
		private final int posStep;
		private final int negStep;

		public PixelFiller(
				final RandomAccessibleInterval<UnsignedByteType> tissue,
				final RandomAccessibleInterval<UnsignedByteType> mask,
				final int stepSize
		) {
			this.tissueAccess = tissue.randomAccess();
			this.maskAccess = mask.randomAccess();
			this.posStep = stepSize;
			this.negStep = -2 * stepSize;
		}

		public int getPixel(final long[] position) {
			if (shouldBeInpainted(position)) {
				return zAverage(position);
			} else {
				return tissueAccess.setPositionAndGet(position).get();
			}
		}

		private int zAverage(final long[] position) {
			maskAccess.setPosition(position);
			maskAccess.move(-1, 2);
			final boolean hasContentAbove = maskAccess.get().get() > 0;
			maskAccess.move(2, 2);
			final boolean hasContentBelow = maskAccess.get().get() > 0;

			tissueAccess.setPositionAndGet(position);
			if (hasContentAbove && hasContentBelow) {
				tissueAccess.move(-1, 2);
				final int above = tissueAccess.get().get();

				tissueAccess.move(2, 2);
				final int below = tissueAccess.get().get();

				return UnsignedByteType.getCodedSignedByteChecked((above + below) >>> 1);
			} else if (hasContentAbove) {
				tissueAccess.move(-1, 2);
				return tissueAccess.get().get();
			} else if (hasContentBelow) {
				tissueAccess.move(2, 2);
				return tissueAccess.get().get();
			} else {
				return 0;
			}
		}

		private boolean shouldBeInpainted(final long[] position) {
			final boolean hasContent = maskAccess.setPositionAndGet(position).get() > 0;
			if (hasContent) {
				return false;
			}

			// If the pixel has no content, check the pixels in +/- y direction
			// Only if both have content, the pixel should be inpainted (otherwise, it is a border pixel)
			maskAccess.move(posStep, 1);
			final boolean hasContentFront = maskAccess.get().get() > 0;
			maskAccess.move(negStep, 1);
			final boolean hasContentBack = maskAccess.get().get() > 0;
			if (hasContentFront && hasContentBack) {
				return true;
			}

			// If that is inconclusive, check the pixels in +/- x direction
			maskAccess.move(posStep, 1);
			maskAccess.move(posStep, 0);
			final boolean hasContentRight = maskAccess.get().get() > 0;
			maskAccess.move(negStep, 0);
			final boolean hasContentLeft = maskAccess.get().get() > 0;
			return hasContentRight && hasContentLeft;
		}

	}

	private static class ExtendedAttributes implements Serializable {
		public final DatasetAttributes attrs;
		public final long[] min;

		public ExtendedAttributes(final DatasetAttributes attrs, final long[] min) {
			this.attrs = attrs;
			this.min = min;
		}

		public static ExtendedAttributes read(final N5Reader n5, final String attrsPath, final String minPath) {
			final DatasetAttributes attrs = n5.getDatasetAttributes(attrsPath);
			final long[] min = n5.getAttribute(minPath, "translate", long[].class);
			return new ExtendedAttributes(attrs, min);
		}
	}
}
