package org.janelia.render.client.spark.multisem;


import com.beust.jcommander.Parameter;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.alignment.util.Grid;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


/**
 * Class for inpainting small gaps between tiles in the wafer 60/61 dataset.
 * <p>
 * The regions to inpaint are determined by looking at mask pixels: if an unmasked pixel is encountered,
 * the algorithm looks at the =/- x/y directions in increments of to find non-masked pixels
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

	private N5Reader n5;
	private DatasetAttributes tissueAttributes;
	private DatasetAttributes maskAttributes;
	private long[] tissueMin;
	private long[] maskMin;


	public Wafer6061Inpainter(final Parameters parameters) {
		this.param = parameters;
	}

	public void run() {
		final String output = param.output == null ? "input dataset" : "'" + param.output + "'";
		LOG.info("Inpainting dataset '{}' in '{}' using mask '{}' and writing to {}",
				 param.dataset, param.n5Path, param.mask, output);

		// Read and cache some metadata of the tissue and mask datasets
		// Assume that the tissue is a multiscale pyramid / mask is a standalone dataset
		n5 = new N5Factory().openReader(N5Factory.StorageFormat.N5, param.n5Path);
		tissueAttributes = n5.getDatasetAttributes(param.fullDataset());
		maskAttributes = n5.getDatasetAttributes(param.mask);
		tissueMin = n5.getAttribute(param.dataset, "translate", long[].class);
		maskMin = n5.getAttribute(param.mask, "translate", long[].class);

		if (param.output == null) {
			param.output = param.fullDataset();
			LOG.info("Output dataset equals input dataset. Overwriting blocks in the input dataset '{}'", param.output);
		} else if (n5.exists(param.output)) {
			throw new IllegalArgumentException("Dataset '" + param.output + "' is different from the input dataset and already exists. Stopping.");
		} else {
			LOG.info("Output dataset is '{}'. Creating new dataset.", param.output);
			try (final N5Writer n5Writer = new N5Factory().openWriter(N5Factory.StorageFormat.N5, param.n5Path)) {
				n5Writer.createDataset(param.output, tissueAttributes);
			}
		}

		final List<Grid.Block> blocksToInpaint = getBlocksToInpaint();
		inpaintBlocks(blocksToInpaint);

		n5.close();
	}

	private List<Grid.Block> getBlocksToInpaint() {
		LOG.info("Reading metadata from {}", param.n5Path);

		final List<Grid.Block> tissueBlocks = Grid.create(tissueAttributes.getDimensions(), tissueAttributes.getBlockSize());
		final List<Grid.Block> maskBlocks = Grid.create(maskAttributes.getDimensions(), maskAttributes.getBlockSize());

		// Filter all blocks that either have no mask, or are completely covered by the mask
		LOG.info("Filtering empty mask blocks from {} blocks", maskBlocks.size());
		final Img<UnsignedByteType> mask = N5Utils.open(n5, param.mask);
		final List<Interval> nonHomogeneousMaskBlocks = new ArrayList<>();
		for (final Grid.Block block : maskBlocks) {
			final IntervalView<UnsignedByteType> maskPixels = Views.interval(mask, block);
			if (! isHomogeneous(maskPixels)) {
				nonHomogeneousMaskBlocks.add(block);
			}
		}
		LOG.info("Found {} non-homogeneous mask blocks", nonHomogeneousMaskBlocks.size());

		// Check which tissue blocks are covered by the mask
		final List<Interval> translatedNonHomogeneousMaskBlocks = nonHomogeneousMaskBlocks.stream()
				.map(b -> Intervals.translate(b, maskMin))
				.collect(Collectors.toList());
		final List<Grid.Block> tissueBlocksToInpaint = new ArrayList<>();

		for (final Grid.Block block : tissueBlocks) {
			final Interval blockInterval = Intervals.translate(block, tissueMin);
			for (final Interval maskBlock : translatedNonHomogeneousMaskBlocks) {
				final boolean intervalsAreDisjoint = Intervals.isEmpty(Intervals.intersect(blockInterval, maskBlock));
				if (!intervalsAreDisjoint) {
					tissueBlocksToInpaint.add(block);
					break;
				}
			}
		}
		LOG.info("Found {} tissue blocks to inpaint", tissueBlocksToInpaint.size());

		return tissueBlocksToInpaint;
	}

	private static boolean isHomogeneous(final IterableInterval<UnsignedByteType> pixels) {
		final UnsignedByteType firstPixel = pixels.firstElement();
		for (final UnsignedByteType pixel : pixels) {
			if (pixel.equals(firstPixel)) {
				return false;
			}
		}
		return true;
	}

	private void inpaintBlocks(final List<Grid.Block> blocksToInpaint) {
		final Img<UnsignedByteType> rawTissue = N5Utils.open(n5, param.fullDataset());
		final Img<UnsignedByteType> rawMask = N5Utils.open(n5, param.mask);

		final RandomAccessibleInterval<UnsignedByteType> tissue = Views.translate(rawTissue, tissueMin);
		final RandomAccessible<UnsignedByteType> mask = Views.translate(Views.extendValue(rawMask, 0.0f), maskMin);

		for (final Grid.Block block : blocksToInpaint) {
			final Interval blockInterval = Intervals.translate(block, tissueMin);
			LOG.info("Inpainting block {} at {}", block.gridPosition, blockInterval.minAsLongArray());

			final Img<UnsignedByteType> inpaintedBlock = ArrayImgs.unsignedBytes(blockInterval.dimensionsAsLongArray());

			final Cursor<UnsignedByteType> targetCursor = Views.translate(inpaintedBlock, blockInterval.minAsLongArray()).localizingCursor();
			final long[] location = new long[3];
			final PixelFiller interpolator = new PixelFiller(Views.interval(tissue, blockInterval),
															 Views.interval(mask, blockInterval),
															 param.stepSize);

			while (targetCursor.hasNext()) {
				final UnsignedByteType targetPixel = targetCursor.next();
				targetCursor.localize(location);
				final int value = interpolator.getPixel(location);
				targetPixel.set(value);
			}

			try (final N5Writer n5Writer = new N5Factory().openWriter(N5Factory.StorageFormat.N5, param.n5Path)) {
				N5Utils.saveBlock(inpaintedBlock, n5Writer, param.output, tissueAttributes, block.gridPosition);
			}
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
}
