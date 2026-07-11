package org.janelia.render.client.spark.multisem;


import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import bdv.export.Downsample;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.KDTree;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.util.Grid;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Spark client for filling holes in the tissue of wafer 60/61 N5 volumes.
 * <p>
 * Inputs are (a) an N5 tissue volume (only non-empty blocks stored), (b) an N5 mask marking where image data is
 * present ({@code mask > 0}) vs. missing ({@code mask == 0}), and (c) the acquisition xlog zarr, which provides a
 * per-slab point cloud of beam positions ({@code x_reference}, {@code y_reference} in microns) together with each
 * point's distance to the region of interest ({@code distance_roi}).
 * <p>
 * The client processes the full-resolution ({@code s0}) blocks of the tissue in parallel. For each block it first
 * applies the cheap ROI-distance filter (interpolating {@code distance_roi} at the block center via inverse-distance
 * weighting; blocks whose interpolated distance is {@code >= maxRoiDistance} are skipped without any I/O), then reads
 * the block (skipping absent/empty blocks) and fills every pixel where the mask is 0 with the average of the tissue in
 * the sections above and below (copying the single neighbor at the volume's z-boundary). Before overwriting a modified
 * block, the original block is copied verbatim into a sibling {@code _backup} N5 container. Finally, only the pyramid
 * blocks affected by the modified {@code s0} blocks are re-downsampled (again backing up the originals first), using
 * the same per-block averaging as {@code N5DownsamplerSpark} so the pyramid stays consistent.
 */
public class Wafer6061Inpainter {

	private static final Logger LOG = LoggerFactory.getLogger(Wafer6061Inpainter.class);

	public static class Parameters extends CommandLineParameters {
		@Parameter(
				names = "--n5Path",
				description = "Path to the N5 container holding the tissue and mask (local path or gs://...).",
				required = true)
		public String n5Path;

		@Parameter(
				names = "--dataset",
				description = "Name of the tissue multiscale group; the full-resolution data is at <dataset>/s0.",
				required = true)
		public String dataset;

		@Parameter(
				names = "--mask",
				description = "Name of the binary uint8 mask dataset (same grid as <dataset>/s0). Pass <group>/s0 if the mask is itself a pyramid.",
				required = true)
		public String mask;

		@Parameter(
				names = "--xlogPath",
				description = "Path to the acquisition xlog zarr, e.g. xlog_wafer_61.zarr (local path or gs://...).",
				required = true)
		public String xlogPath;

		@Parameter(
				names = "--serial",
				description = "Serial label (id_serial) of the section stored in this N5; resolved to the reference arrays' slab position.",
				required = true)
		public long serial;

		@Parameter(
				names = "--scale",
				description = "Nanometers per pixel used to map a block center to the xlog micron frame: micron = (pixel + translate) * scale / 1000.")
		public double scale = 8.0;

		@Parameter(
				names = "--maxRoiDistance",
				description = "Keep (inpaint) a block only if its interpolated distance_roi (microns) is less than this.")
		public double maxRoiDistance = 10.0;

		@Parameter(
				names = "--k",
				description = "Number of nearest neighbors used for inverse-distance weighting.")
		public int k = 8;

		@Parameter(
				names = "--idwPower",
				description = "Power p in the inverse-distance weights 1 / r^p.")
		public double idwPower = 2.0;

		@Parameter(
				names = "--downsampleFactors",
				description = "Relative per-level downsampling factors of the existing pyramid, e.g. 2,2,1.")
		public String downsampleFactors = "2,2,1";

		@Parameter(
				names = "--backupPath",
				description = "N5 container where original (overwritten) blocks are backed up. Defaults to a sibling <container>_backup.n5.")
		public String backupPath;

		@Parameter(
				names = "--dryRun",
				description = "Only compute and log candidate / would-be-modified counts; do not write or back up anything.")
		public boolean dryRun = false;

		public String fullDataset() {
			return dataset + "/s0";
		}

		public int[] getDownsampleFactors() {
			final String[] parts = downsampleFactors.split(",");
			final int[] factors = new int[parts.length];
			for (int i = 0; i < parts.length; i++) {
				factors[i] = Integer.parseInt(parts[i].trim());
			}
			return factors;
		}

		public String getBackupPath() {
			if (backupPath != null) {
				return backupPath;
			}
			String p = n5Path;
			while (p.endsWith("/")) {
				p = p.substring(0, p.length() - 1);
			}
			if (p.endsWith(".n5")) {
				return p.substring(0, p.length() - 3) + "_backup.n5";
			}
			return p + "_backup.n5";
		}

		public void validate() {
			if (scale <= 0) {
				throw new IllegalArgumentException("--scale must be positive");
			}
			if (maxRoiDistance <= 0) {
				throw new IllegalArgumentException("--maxRoiDistance must be positive");
			}
			if (k < 1) {
				throw new IllegalArgumentException("--k must be at least 1");
			}
		}
	}

	private final Parameters params;

	public Wafer6061Inpainter(final Parameters params) {
		this.params = params;
	}

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				parameters.validate();

				LOG.info("runClient: entry, parameters={}", parameters);

				final Wafer6061Inpainter client = new Wafer6061Inpainter(parameters);
				client.run();
			}
		};
		clientRunner.run();
	}

	public void run() throws IOException {

		// 1. Load the (small) per-slab 2-D point cloud from the xlog on the driver.
		final PointCloud cloud = loadPointCloud(params.xlogPath, params.serial);
		LOG.info("run: loaded {} ROI reference points for serial {}", cloud.size(), params.serial);

		// Read the tissue s0 metadata and discover the existing pyramid levels.
		final long[] tissueTranslate;
		final long[] maskTranslate;
		final int numDimensions;
		final List<String> levels = new ArrayList<>();
		final Map<String, DatasetAttributes> levelAttributes = new LinkedHashMap<>();
		try (final N5Reader n5 = new N5Factory().openReader(N5Factory.StorageFormat.N5, params.n5Path)) {

			final DatasetAttributes s0Attributes = n5.getDatasetAttributes(params.fullDataset());
			if (s0Attributes == null) {
				throw new IllegalArgumentException("tissue dataset " + params.fullDataset() + " does not exist");
			}
			numDimensions = s0Attributes.getNumDimensions();
			if (numDimensions != 3) {
				throw new IllegalArgumentException("expected a 3D tissue volume but " + params.fullDataset() +
												   " has " + numDimensions + " dimensions");
			}
			if (n5.getDatasetAttributes(params.mask) == null) {
				throw new IllegalArgumentException("mask dataset " + params.mask + " does not exist");
			}

			tissueTranslate = readTranslate(n5, params.fullDataset(), numDimensions);
			maskTranslate = readTranslate(n5, params.mask, numDimensions);

			levels.add(params.fullDataset());
			levelAttributes.put(params.fullDataset(), s0Attributes);
			for (int scale = 1; ; scale++) {
				final String levelDataset = params.dataset + "/s" + scale;
				if (! n5.datasetExists(levelDataset)) {
					break;
				}
				levels.add(levelDataset);
				levelAttributes.put(levelDataset, n5.getDatasetAttributes(levelDataset));
			}
		}
		LOG.info("run: tissue translate={}, mask translate={}, pyramid levels={}",
				 Arrays.toString(tissueTranslate), Arrays.toString(maskTranslate), levels);

		// Create the backup container and mirror all datasets that might receive backups.
		final String backupPath = params.getBackupPath();
		if (! params.dryRun) {
			try (final N5Writer backup = new N5Factory().openWriter(N5Factory.StorageFormat.N5, backupPath)) {
				for (final String levelDataset : levels) {
					if (! backup.datasetExists(levelDataset)) {
						backup.createDataset(levelDataset, levelAttributes.get(levelDataset));
					}
				}
			}
			LOG.info("run: originals will be backed up to {}", backupPath);
		}

		final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
		try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
			LOG.info("run: appId is {}", sparkContext.getConf().getAppId());
			runWithSparkContext(sparkContext, cloud, tissueTranslate, maskTranslate,
								levels, levelAttributes, backupPath);
		}
	}

	private void runWithSparkContext(final JavaSparkContext sparkContext,
									 final PointCloud cloud,
									 final long[] tissueTranslate,
									 final long[] maskTranslate,
									 final List<String> levels,
									 final Map<String, DatasetAttributes> levelAttributes,
									 final String backupPath) {

		final DatasetAttributes s0Attributes = levelAttributes.get(params.fullDataset());
		final List<Grid.Block> s0Blocks = Grid.create(s0Attributes.getDimensions(), s0Attributes.getBlockSize());
		LOG.info("runWithSparkContext: {} s0 grid blocks to consider", s0Blocks.size());

		final Broadcast<PointCloud> cloudBroadcast = sparkContext.broadcast(cloud);
		final Broadcast<Parameters> paramsBroadcast = sparkContext.broadcast(params);

		// 2. + 3. Filter (distance, then presence) and inpaint in one distributed pass.
		final String backup = params.dryRun ? null : backupPath;
		final JavaRDD<long[]> modifiedRDD = sparkContext.parallelize(s0Blocks).mapPartitions(
				blockIterator -> inpaintPartition(blockIterator,
												  cloudBroadcast.value(),
												  paramsBroadcast.value(),
												  tissueTranslate,
												  maskTranslate,
												  backup));

		final List<long[]> modifiedS0 = modifiedRDD.collect();
		LOG.info("runWithSparkContext: {} s0 block(s) were {}",
				 modifiedS0.size(), params.dryRun ? "identified for inpainting (dry run)" : "inpainted");

		if (params.dryRun || modifiedS0.isEmpty()) {
			return;
		}

		// 6. Selectively update the downsample pyramid, one level at a time.
		updatePyramid(sparkContext, levels, levelAttributes, modifiedS0, backupPath);
	}

	// ------------------------------------------------------------------------------------------------
	// Step 1: load the per-slab 2-D point cloud from the xlog zarr.
	// ------------------------------------------------------------------------------------------------

	/**
	 * Reads {@code x_reference}, {@code y_reference} and {@code distance_roi} for the slab whose {@code id_serial}
	 * label equals {@code serial}, flattening the mfov x sfov grid into a single 2-D cloud (NaN points dropped).
	 */
	static PointCloud loadPointCloud(final String xlogPath, final long serial) {
		try (final N5Reader xlog = new N5Factory().openReader(xlogPath)) {

			final double[] idSerial = read1d(xlog, "id_serial");
			final int slabPosition = findSlabPosition(idSerial, serial);
			if (slabPosition < 0) {
				final long[] available = new long[idSerial.length];
				for (int i = 0; i < idSerial.length; i++) {
					available[i] = Math.round(idSerial[i]);
				}
				throw new IllegalArgumentException("serial " + serial + " not found in id_serial; available serials are " +
												   Arrays.toString(available));
			}

			// The slab axis is the one whose size matches the length of id_serial (413 for wafer 61).
			final long slabCount = idSerial.length;
			final RandomAccessibleInterval<DoubleType> xSlab = readSlab(xlog, "x_reference", slabCount, slabPosition);
			final RandomAccessibleInterval<DoubleType> ySlab = readSlab(xlog, "y_reference", slabCount, slabPosition);
			final RandomAccessibleInterval<DoubleType> distSlab = readSlab(xlog, "distance_roi", slabCount, slabPosition);

			final List<Double> xs = new ArrayList<>();
			final List<Double> ys = new ArrayList<>();
			final List<Double> dists = new ArrayList<>();

			final Cursor<DoubleType> xc = xSlab.localizingCursor();
			final RandomAccess<DoubleType> yra = ySlab.randomAccess();
			final RandomAccess<DoubleType> dra = distSlab.randomAccess();
			final long[] pos = new long[xSlab.numDimensions()];
			while (xc.hasNext()) {
				final double x = xc.next().get();
				xc.localize(pos);
				final double y = yra.setPositionAndGet(pos).get();
				final double d = dra.setPositionAndGet(pos).get();
				if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(d)) {
					xs.add(x);
					ys.add(y);
					dists.add(d);
				}
			}

			if (xs.isEmpty()) {
				throw new IllegalArgumentException("no finite ROI reference points found for serial " + serial +
												   " (slab position " + slabPosition + ")");
			}
			return new PointCloud(toArray(xs), toArray(ys), toArray(dists));
		}
	}

	/** Reads a 1-D double dataset (blosc-safe: uses the block-not-found overload to avoid the getAttribute NPE). */
	private static double[] read1d(final N5Reader n5, final String dataset) {
		final RandomAccessibleInterval<DoubleType> img = openDoubles(n5, dataset);
		final long length = img.dimension(0);
		final double[] values = new double[(int) length];
		final RandomAccess<DoubleType> ra = img.randomAccess();
		for (int i = 0; i < length; i++) {
			values[i] = ra.setPositionAndGet(new long[] {i}).get();
		}
		return values;
	}

	/** Opens a 3-D reference array and returns the 2-D slice for the given slab position. */
	private static RandomAccessibleInterval<DoubleType> readSlab(final N5Reader n5,
																final String dataset,
																final long slabCount,
																final int slabPosition) {
		final RandomAccessibleInterval<DoubleType> img = openDoubles(n5, dataset);
		int slabAxis = -1;
		for (int d = 0; d < img.numDimensions(); d++) {
			if (img.dimension(d) == slabCount) {
				slabAxis = d;
				break;
			}
		}
		if (slabAxis < 0) {
			throw new IllegalArgumentException("could not find slab axis (size " + slabCount + ") in " + dataset +
											   " with dimensions " + Arrays.toString(img.dimensionsAsLongArray()));
		}
		return Views.hyperSlice(img, slabAxis, slabPosition);
	}

	/**
	 * Opens a double-typed dataset using the {@code (blockNotFoundHandler, accessFlags)} overload. This avoids
	 * {@code N5Utils.open(reader, dataset)}'s {@code isLabelMultisetType -> getAttribute} path, which NPEs on blosc
	 * arrays in the shaded jar (the n5-blosc CompressionType service registration is filtered out). Missing blocks
	 * are filled with NaN so they are dropped downstream.
	 */
	private static RandomAccessibleInterval<DoubleType> openDoubles(final N5Reader n5, final String dataset) {
		final Consumer<IterableInterval<DoubleType>> nanFill = it -> it.forEach(t -> t.set(Double.NaN));
		return N5Utils.open(n5, dataset, nanFill, AccessFlags.setOf());
	}

	// ------------------------------------------------------------------------------------------------
	// Steps 2 + 3: filter and inpaint one partition of s0 blocks.
	// ------------------------------------------------------------------------------------------------

	private static Iterator<long[]> inpaintPartition(final Iterator<Grid.Block> blocks,
													 final PointCloud cloud,
													 final Parameters params,
													 final long[] tissueTranslate,
													 final long[] maskTranslate,
													 final String backupPath) {

		LogUtilities.setupExecutorLog4j("inpaint");

		final List<long[]> modified = new ArrayList<>();
		final KNearestNeighborSearchOnKDTree<DoubleType> search = cloud.buildSearch(params.k);
		final double micronPerPixel = params.scale / 1000.0;

		try (final N5Reader reader = new N5Factory().openReader(N5Factory.StorageFormat.N5, params.n5Path);
			 final N5Writer tissueWriter = params.dryRun ? null :
					 new N5Factory().openWriter(N5Factory.StorageFormat.N5, params.n5Path);
			 final N5Writer backupWriter = params.dryRun ? null :
					 new N5Factory().openWriter(N5Factory.StorageFormat.N5, backupPath)) {

			final DatasetAttributes s0Attributes = reader.getDatasetAttributes(params.fullDataset());

			final Img<UnsignedByteType> rawTissue = N5Utils.open(reader, params.fullDataset());
			final Img<UnsignedByteType> rawMask = N5Utils.open(reader, params.mask);
			final RandomAccessibleInterval<UnsignedByteType> tissue = Views.translate(rawTissue, tissueTranslate);
			final RandomAccessibleInterval<UnsignedByteType> mask = Views.translate(rawMask, maskTranslate);

			final long zMin = tissueTranslate[2];
			final long zMax = tissueTranslate[2] + s0Attributes.getDimensions()[2] - 1;

			while (blocks.hasNext()) {
				final Grid.Block block = blocks.next();

				// (1) ROI-distance filter, computed with no I/O.
				final double centerX = (block.offset[0] + block.dimensions[0] / 2.0 + tissueTranslate[0]) * micronPerPixel;
				final double centerY = (block.offset[1] + block.dimensions[1] / 2.0 + tissueTranslate[1]) * micronPerPixel;
				final double roiDistance = cloud.interpolate(search, centerX, centerY, params.idwPower);
				if (! (roiDistance < params.maxRoiDistance)) {
					continue;
				}

				// (2) presence check: readBlock returns null for absent (empty) blocks.
				final DataBlock<?> originalBlock = reader.readBlock(params.fullDataset(), s0Attributes, block.gridPosition);
				if (originalBlock == null) {
					continue;
				}

				// (3) inpaint: fill mask==0 pixels with the z-average of the sections above/below.
				final InpaintResult result = inpaintBlock(tissue, mask, block, tissueTranslate, zMin, zMax);
				if (! result.changed) {
					continue;
				}

				modified.add(block.gridPosition);
				if (! params.dryRun) {
					backupWriter.writeBlock(params.fullDataset(), s0Attributes, originalBlock);
					N5Utils.saveBlock(result.inpainted, tissueWriter, params.fullDataset(), s0Attributes, block.gridPosition);
				}
				LOG.info("inpaintPartition: inpainted block {} (roiDistance={})",
						 Arrays.toString(block.gridPosition), roiDistance);
			}
		}

		return modified.iterator();
	}

	/**
	 * Produces the inpainted version of a single block: every pixel where the mask is 0 is replaced with the average
	 * of the tissue in the sections above and below (the single neighbor is copied at the volume's z-boundary). The
	 * {@code tissue} and {@code mask} are expected to be world-translated (i.e. indexed in the same coordinate frame,
	 * offset by their respective {@code translate}); {@code zMin}/{@code zMax} are the world z-bounds of the tissue.
	 */
	static InpaintResult inpaintBlock(final RandomAccessibleInterval<UnsignedByteType> tissue,
									  final RandomAccessibleInterval<UnsignedByteType> mask,
									  final Grid.Block block,
									  final long[] tissueTranslate,
									  final long zMin,
									  final long zMax) {

		final RandomAccess<UnsignedByteType> tissueAccess = tissue.randomAccess();
		final RandomAccess<UnsignedByteType> maskAccess = Views.extendZero(mask).randomAccess();

		final Img<UnsignedByteType> inpainted = ArrayImgs.unsignedBytes(block.dimensions);
		final Cursor<UnsignedByteType> cursor = inpainted.localizingCursor();
		final long[] local = new long[3];
		final long[] world = new long[3];
		boolean changed = false;
		while (cursor.hasNext()) {
			final UnsignedByteType target = cursor.next();
			cursor.localize(local);
			world[0] = block.offset[0] + tissueTranslate[0] + local[0];
			world[1] = block.offset[1] + tissueTranslate[1] + local[1];
			world[2] = block.offset[2] + tissueTranslate[2] + local[2];

			final int original = tissueAccess.setPositionAndGet(world).get();
			final int value;
			if (maskAccess.setPositionAndGet(world).get() > 0) {
				value = original;
			} else {
				value = zAverage(tissueAccess, world, zMin, zMax);
				if (value != original) {
					changed = true;
				}
			}
			target.set(value);
		}

		return new InpaintResult(inpainted, changed);
	}

	/** Average of the tissue in the z-1 and z+1 sections, copying the single neighbor at the volume z-boundary. */
	private static int zAverage(final RandomAccess<UnsignedByteType> tissueAccess,
								final long[] world,
								final long zMin,
								final long zMax) {
		final long z = world[2];
		final boolean hasAbove = (z - 1) >= zMin;
		final boolean hasBelow = (z + 1) <= zMax;
		if (hasAbove && hasBelow) {
			world[2] = z - 1;
			final int above = tissueAccess.setPositionAndGet(world).get();
			world[2] = z + 1;
			final int below = tissueAccess.setPositionAndGet(world).get();
			world[2] = z;
			return (above + below) >>> 1;
		} else if (hasAbove) {
			world[2] = z - 1;
			final int above = tissueAccess.setPositionAndGet(world).get();
			world[2] = z;
			return above;
		} else if (hasBelow) {
			world[2] = z + 1;
			final int below = tissueAccess.setPositionAndGet(world).get();
			world[2] = z;
			return below;
		} else {
			return tissueAccess.setPositionAndGet(world).get();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Step 6: selectively re-downsample only the pyramid blocks affected by the modified s0 blocks.
	// ------------------------------------------------------------------------------------------------

	private void updatePyramid(final JavaSparkContext sparkContext,
							   final List<String> levels,
							   final Map<String, DatasetAttributes> levelAttributes,
							   final List<long[]> modifiedS0,
							   final String backupPath) {

		final int[] factors = params.getDownsampleFactors();
		List<long[]> modifiedPrevious = modifiedS0;

		for (int scale = 1; scale < levels.size(); scale++) {
			final String fromDataset = levels.get(scale - 1);
			final String toDataset = levels.get(scale);
			final DatasetAttributes toAttributes = levelAttributes.get(toDataset);

			// affected blocks: previous-level grid position p maps to this-level block p / factor.
			final Map<String, long[]> affected = new LinkedHashMap<>();
			for (final long[] p : modifiedPrevious) {
				final long[] g = affectedBlock(p, factors);
				affected.putIfAbsent(Arrays.toString(g), g);
			}
			final List<long[]> affectedBlocks = new ArrayList<>(affected.values());
			LOG.info("updatePyramid: re-downsampling {} block(s) for {}", affectedBlocks.size(), toDataset);

			final String n5Path = params.n5Path;
			sparkContext.parallelize(affectedBlocks).foreach(
					gridPosition -> downsampleBlock(gridPosition, n5Path, backupPath,
													fromDataset, toDataset, toAttributes, factors));

			modifiedPrevious = affectedBlocks;
		}
	}

	/**
	 * Re-downsamples a single pyramid block from the (already updated) previous level, replicating the per-block
	 * math of {@code N5DownsamplerSpark} so the result matches the rest of the pyramid. The original block is backed
	 * up before being overwritten.
	 */
	static void downsampleBlock(final long[] gridPosition,
										final String n5Path,
										final String backupPath,
										final String fromDataset,
										final String toDataset,
										final DatasetAttributes toAttributes,
										final int[] factors) {

		LogUtilities.setupExecutorLog4j("downsample");

		final int n = toAttributes.getNumDimensions();
		final CellGrid cellGrid = new CellGrid(toAttributes.getDimensions(), toAttributes.getBlockSize());

		final long[] targetMin = new long[n];
		final int[] cellDimensions = new int[n];
		cellGrid.getCellDimensions(gridPosition, targetMin, cellDimensions);

		final long[] sourceMin = new long[n];
		final long[] sourceSize = new long[n];
		final long[] targetSize = new long[n];
		for (int d = 0; d < n; d++) {
			sourceMin[d] = targetMin[d] * factors[d];
			sourceSize[d] = (long) cellDimensions[d] * factors[d];
			targetSize[d] = cellDimensions[d];
		}

		try (final N5Reader reader = new N5Factory().openReader(N5Factory.StorageFormat.N5, n5Path);
			 final N5Writer writer = new N5Factory().openWriter(N5Factory.StorageFormat.N5, n5Path);
			 final N5Writer backupWriter = new N5Factory().openWriter(N5Factory.StorageFormat.N5, backupPath)) {

			final RandomAccessibleInterval<UnsignedByteType> source = N5Utils.open(reader, fromDataset);
			final RandomAccessibleInterval<UnsignedByteType> sourceBlock = Views.offsetInterval(source, sourceMin, sourceSize);

			final Img<UnsignedByteType> targetBlock = ArrayImgs.unsignedBytes(targetSize);
			Downsample.downsample(sourceBlock, targetBlock, factors);

			// back up the original block (if present) before overwriting.
			final DataBlock<?> originalBlock = reader.readBlock(toDataset, toAttributes, gridPosition);
			if (originalBlock != null) {
				backupWriter.writeBlock(toDataset, toAttributes, originalBlock);
			}

			// delete first so a block that became empty does not leave a stale remnant.
			N5Utils.deleteBlock(targetBlock, writer, toDataset, gridPosition);
			N5Utils.saveNonEmptyBlock(targetBlock, writer, toDataset, gridPosition, new UnsignedByteType());
			LOG.info("downsampleBlock: updated {} block {}", toDataset, Arrays.toString(gridPosition));
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------------

	/** Returns the slab-axis position whose id_serial label equals {@code serial}, or -1 if none matches. */
	static int findSlabPosition(final double[] idSerial, final long serial) {
		for (int i = 0; i < idSerial.length; i++) {
			if (Math.round(idSerial[i]) == serial) {
				return i;
			}
		}
		return -1;
	}

	/** Maps a grid block position at pyramid level k-1 to the block it feeds at level k (per-dimension p / factor). */
	static long[] affectedBlock(final long[] previousGridPosition, final int[] factors) {
		final long[] g = new long[previousGridPosition.length];
		for (int d = 0; d < g.length; d++) {
			g[d] = previousGridPosition[d] / factors[d];
		}
		return g;
	}

	private static long[] readTranslate(final N5Reader n5, final String dataset, final int numDimensions) {
		final long[] translate = n5.getAttribute(dataset, "translate", long[].class);
		return translate != null ? translate : new long[numDimensions];
	}

	private static double[] toArray(final List<Double> values) {
		final double[] array = new double[values.size()];
		for (int i = 0; i < array.length; i++) {
			array[i] = values.get(i);
		}
		return array;
	}

	/** Result of inpainting one block: the (block-sized) inpainted image and whether any pixel changed. */
	static class InpaintResult {
		final Img<UnsignedByteType> inpainted;
		final boolean changed;

		InpaintResult(final Img<UnsignedByteType> inpainted, final boolean changed) {
			this.inpainted = inpainted;
			this.changed = changed;
		}
	}

	/** Serializable 2-D point cloud of ROI reference points with a distance value per point. */
	static class PointCloud implements Serializable {
		private final double[] xs;
		private final double[] ys;
		private final double[] dists;

		PointCloud(final double[] xs, final double[] ys, final double[] dists) {
			this.xs = xs;
			this.ys = ys;
			this.dists = dists;
		}

		int size() {
			return xs.length;
		}

		KNearestNeighborSearchOnKDTree<DoubleType> buildSearch(final int k) {
			final List<RealPoint> points = new ArrayList<>(xs.length);
			final List<DoubleType> values = new ArrayList<>(xs.length);
			for (int i = 0; i < xs.length; i++) {
				points.add(new RealPoint(xs[i], ys[i]));
				values.add(new DoubleType(dists[i]));
			}
			final KDTree<DoubleType> tree = new KDTree<>(values, points);
			return new KNearestNeighborSearchOnKDTree<>(tree, Math.min(k, xs.length));
		}

		/** Inverse-distance-weighted interpolation of the distance value at (x, y). */
		double interpolate(final KNearestNeighborSearchOnKDTree<DoubleType> search,
						   final double x,
						   final double y,
						   final double power) {
			search.search(new RealPoint(x, y));
			final int numNeighbors = search.getK();
			double numerator = 0;
			double denominator = 0;
			for (int i = 0; i < numNeighbors; i++) {
				final double r = search.getDistance(i);
				final double v = search.getSampler(i).get().get();
				if (r == 0.0) {
					return v;
				}
				final double w = 1.0 / Math.pow(r, power);
				numerator += w * v;
				denominator += w;
			}
			return numerator / denominator;
		}
	}
}
