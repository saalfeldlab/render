package org.janelia.alignment.inpainting;

import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.alignment.util.Grid;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Inpaint missing values in a small area of an image by interpolating along the z-axis. A user supplied mask with
 * values in the interval [0, 1] is used to determine the area to inpaint. The content to inpaint is computed by linearly
 * interpolating the z-layers right before and after the missing area.
 */
public class InterpolatingZInpainter {

	private static final Logger LOG = LoggerFactory.getLogger(InterpolatingZInpainter.class);

	private final String n5Path;
	private final String dataset;
	private final String maskDataset;
	private final String outputDataset;

	private N5Reader n5;
	private DatasetAttributes tissueAttributes;
	private DatasetAttributes maskAttributes;
	private long[] tissueMin;
	private long[] maskMin;

	/**
	 * @param n5Path path to the N5 container containing the data and the mask
	 * @param dataset name of the dataset to inpaint; assumed to be a multiscale pyramid, only s0 is inpainted
	 * @param maskDataset name of the mask dataset; this is supposed to only cover a small z-range of the dataset and
	 *                    translated to the correct location in the stack coordinates (with an attribute "translate"
	 *                    containing the translation vector)
	 * @param outputDataset name of the dataset to write the inpainted data to; only blocks that are inpainted are written
	 */
	public InterpolatingZInpainter(
			final String n5Path,
			final String dataset,
			final String maskDataset,
			final String outputDataset) {
		this.n5Path = n5Path;
		this.dataset = dataset;
		this.maskDataset = maskDataset;
		this.outputDataset = outputDataset;
	}

	public void inpaint() {
		LOG.info("Inpainting {} in {} using mask {} and writing to {}", dataset, n5Path, maskDataset, outputDataset);

		// Read and cache some metadata of the tissue and mask datasets
		// Assume that the tissue is a multiscale pyramid / mask is a standalone dataset
		n5 = new N5FSReader(n5Path);
		tissueAttributes = n5.getDatasetAttributes(dataset + "/s0");
		maskAttributes = n5.getDatasetAttributes(maskDataset);
		tissueMin = n5.getAttribute(dataset, "translate", long[].class);
		maskMin = n5.getAttribute(maskDataset, "translate", long[].class);

		if (n5.exists(outputDataset)) {
			throw new RuntimeException("Dataset '" + outputDataset + "' already exists");
		}

		final List<Grid.Block> blocksToInpaint = getBlocksToInpaint();
		final Interpolator interpolator = getInterpolator();
		inpaintBlocks(blocksToInpaint, interpolator);

		n5.close();
	}

	private List<Grid.Block> getBlocksToInpaint() {
		LOG.info("Reading metadata from {}", n5Path);

		final List<Grid.Block> tissueBlocks = Grid.create(tissueAttributes.getDimensions(), tissueAttributes.getBlockSize());
		final List<Grid.Block> maskBlocks = Grid.create(maskAttributes.getDimensions(), maskAttributes.getBlockSize());

		// Filter empty blocks in the mask
		LOG.info("Filtering empty mask blocks from {} blocks", maskBlocks.size());
		final Img<FloatType> mask = N5Utils.open(n5, maskDataset);
		final List<Interval> nonEmptyMaskBlocks = new ArrayList<>();
		for (final Grid.Block block : maskBlocks) {
			final IntervalView<FloatType> pixels = Views.interval(mask, block);
			for (final FloatType pixel : pixels) {
				if (pixel.get() > 0) {
					nonEmptyMaskBlocks.add(block);
					break;
				}
			}
		}
		LOG.info("Found {} non-empty mask blocks", nonEmptyMaskBlocks.size());

		// See which tissue blocks are covered by the mask
		final List<Interval> translatedNonEmptyMaskBlocks = nonEmptyMaskBlocks.stream()
				.map(b -> Intervals.translate(b, maskMin))
				.collect(Collectors.toList());
		final List<Grid.Block> tissueBlocksToInpaint = new ArrayList<>();

		for (final Grid.Block block : tissueBlocks) {
			final Interval blockInterval = Intervals.translate(block, tissueMin);
			for (final Interval maskBlock : translatedNonEmptyMaskBlocks) {
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

	private Interpolator getInterpolator() {
		final Img<UnsignedByteType> tissue = N5Utils.open(n5, dataset + "/s0");
		final RandomAccessibleInterval<UnsignedByteType> translatedTissue = Views.translate(tissue, tissueMin);

		final long firstSliceZ = maskMin[2] - 1;
		final long lastSliceZ = maskMin[2] + maskAttributes.getDimensions()[2];
		final RandomAccessibleInterval<UnsignedByteType> firstSlice = Views.hyperSlice(translatedTissue, 2, firstSliceZ);
		final RandomAccessibleInterval<UnsignedByteType> lastSlice = Views.hyperSlice(translatedTissue, 2, lastSliceZ);

		return new Interpolator(firstSlice, lastSlice, firstSliceZ, lastSliceZ);
	}

	private void inpaintBlocks(final List<Grid.Block> blocksToInpaint, final Interpolator interpolator) {
		final Img<UnsignedByteType> rawTissue = N5Utils.open(n5, dataset + "/s0");
		final Img<FloatType> rawMask = N5Utils.open(n5, maskDataset);

		final RandomAccessibleInterval<UnsignedByteType> tissue = Views.translate(rawTissue, tissueMin);
		final RandomAccessible<FloatType> mask = Views.translate(Views.extendValue(rawMask, 0.0f), maskMin);

		final N5Writer n5Writer = new N5FSWriter(n5Path);
		n5Writer.createDataset(outputDataset, tissueAttributes);

		for (final Grid.Block block : blocksToInpaint) {
			final Interval blockInterval = Intervals.translate(block, tissueMin);
			LOG.info("Inpainting block at {}", blockInterval.minAsLongArray());

			final Img<UnsignedByteType> inpaintedBlock = ArrayImgs.unsignedBytes(blockInterval.dimensionsAsLongArray());

			final Cursor<UnsignedByteType> cursor = Views.translate(inpaintedBlock, blockInterval.minAsLongArray()).localizingCursor();
			final RandomAccess<UnsignedByteType> tissueAccess = Views.interval(tissue, blockInterval).randomAccess();
			final RandomAccess<FloatType> maskAccess = Views.interval(mask, blockInterval).randomAccess();
			final long[] location = new long[3];

			while (cursor.hasNext()) {
				final UnsignedByteType targetPixel = cursor.next();
				cursor.localize(location);

				final int tissuePixel = tissueAccess.setPositionAndGet(location).get();
				final float maskPixel = maskAccess.setPositionAndGet(location).get();

				if (maskPixel == 0.0f) {
					targetPixel.set(tissuePixel);
				} else {
					final int interpolatedPixel = interpolator.getAt(location);
					final byte blendedValue = UnsignedByteType.getCodedSignedByteChecked((int) (tissuePixel * (1 - maskPixel) + interpolatedPixel * maskPixel));
					targetPixel.set(blendedValue);
				}
			}

			N5Utils.saveBlock(inpaintedBlock, n5Writer, outputDataset, tissueAttributes, block.gridPosition);
		}

		n5Writer.close();
	}


	public static void main(final String[] args) {
		final String n5Path = "/nrs/fibsem/data/jrc_P3-E2-D1-Lip4-19/jrc_P3-E2-D1-Lip4-19.n5";
		final String dataset = "render/jrc_P3_E2_D1_Lip4_19/v1_acquire_trimmed_align_v2_destreak___20250114_154257";
		final String maskDataset = "render/jrc_P3_E2_D1_Lip4_19/smooth-mask_20250114";
		final String outputDataset = "render/jrc_P3_E2_D1_Lip4_19/inpainted_blocks";

		final InterpolatingZInpainter inpainter = new InterpolatingZInpainter(n5Path, dataset, maskDataset, outputDataset);
		inpainter.inpaint();
	}


	/**
	 * Interpolates linearly between two slices. Expects the slices to be placed in the stack coordinate system.
	 */
	private static class Interpolator {
		private final RandomAccess<UnsignedByteType> firstSliceAccess;
		private final RandomAccess<UnsignedByteType> lastSliceAccess;
		private final long firstLayerZ;
		private final long nLayers;

		public Interpolator(
				final RandomAccessibleInterval<UnsignedByteType> firstSlice,
				final RandomAccessibleInterval<UnsignedByteType> lastSlice,
				final long firstLayerZ,
				final long lastLayerZ
		) {
			// Copy slices into proper 2D images to not keep all the blocks in memory
			LOG.info("Interpolating between slices with z={} and z={}", firstLayerZ, lastLayerZ);
			firstSliceAccess = cloneSlice(firstSlice).randomAccess();
			lastSliceAccess = cloneSlice(lastSlice).randomAccess();

			this.firstLayerZ = firstLayerZ;
			nLayers = lastLayerZ - firstLayerZ;
		}

		private RandomAccessibleInterval<UnsignedByteType> cloneSlice(final RandomAccessibleInterval<UnsignedByteType> slice) {
			final RandomAccessibleInterval<UnsignedByteType> lazySlice = Views.dropSingletonDimensions(slice);
			final Img<UnsignedByteType> sliceImg = ArrayImgs.unsignedBytes(lazySlice.dimensionsAsLongArray());
			LoopBuilder.setImages(lazySlice, sliceImg).forEachPixel((i, o) -> o.set(i));
			return Views.translate(sliceImg, slice.minAsLongArray());
		}

		public int getAt(final long[] position) {
			final long x = position[0];
			final long y = position[1];
			final long z = position[2];
			final double t = (double) (z - firstLayerZ) / nLayers;
			final int firstValue = firstSliceAccess.setPositionAndGet(x, y).get();
			final int lastValue = lastSliceAccess.setPositionAndGet(x, y).get();
			return (int) (firstValue * (1 - t) + lastValue * t);
		}
	}
}
