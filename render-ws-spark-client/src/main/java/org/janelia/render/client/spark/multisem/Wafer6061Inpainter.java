package org.janelia.render.client.spark.multisem;


import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.util.Grid;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
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
 * present ({@code mask > 0}) vs. missing ({@code mask == 0}), and (c) the acquisition xlog zarr. The ROI point cloud
 * is built in the tissue's s0 voxel frame from the ALIGNED render stack (so montage stitching is accounted for): the
 * first layer's tile bounds are fetched from the render web service on the driver, and each tile center (render world
 * pixels) is mapped to the voxel frame with {@code voxel = center - translate} (the neuroglancer group-level
 * {@code translate}, i.e. the stack bounding-box min). Each tile carries the {@code distance_roi} of its SFOV, read
 * from the xlog for the slab whose {@code id_serial} equals {@code serial}: the tile's mfov (0-based) indexes the xlog
 * mfov axis directly, and its sfov number (the 1-based {@code _s##} field) maps directly to the 0-based xlog sfov axis
 * as {@code _s## - 1}.
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

	// Inverse-distance-weighting knobs for the ROI-distance interpolation; fixed for wafer 60/61.
	private static final int IDW_K = 8;
	private static final double IDW_POWER = 2.0;

	// Tissue containers/datasets are named w<wafer>_s<serial>_r<revision> (e.g. w61_s109_r00); the serial is parsed from that.
	private static final Pattern SERIAL_IN_NAME = Pattern.compile("_s(\\d+)");

	// xlog field layout (consumed by loadPointCloud). The xlog is a zarr: its arrays are stored C-order, but the
	// n5-zarr reader REVERSES the axis order, so the imglib2 axis indices below are the reverse of the C-order shape.
	//   field          C-order shape        imglib2 axes (what this code sees)   meaning
	//   id_serial       (slab,)              [slab]                              serial label per slab position
	//   distance_roi    (slab, mfov, sfov)   [sfov, mfov, slab]                  distance to ROI, um (scan-independent)
	// The slab axis index is HARDCODED (below) rather than discovered by matching sizes (sizes are not guaranteed to
	// be unique). After slicing the slab axis, distance_roi reduces to a 2-D (sfov, mfov) plane.
	private static final int DIST_AXIS_SLAB = 2;

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
				description = "Serial label (id_serial) of the section stored in this N5, resolved to the reference " +
							  "arrays' slab position. Defaults to the serial parsed from the dataset name " +
							  "(w<wafer>_s<serial>_r<revision>, e.g. w61_s109_r00 -> 109).")
		public long serial = -1;

		@Parameter(
				names = "--maxRoiDistance",
				description = "Keep (inpaint) a block only if its interpolated distance_roi (microns) is less than this.")
		public double maxRoiDistance = 10.0;

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
			if (maxRoiDistance <= 0) {
				throw new IllegalArgumentException("--maxRoiDistance must be positive");
			}
			if (serial < 0) {
				serial = inferSerial(dataset);
			}
		}

		/** Parses the serial label from a dataset/container name like {@code .../w61_s109_r00} (basename {@code _s<serial>}). */
		static long inferSerial(final String path) {
			final Matcher matcher = SERIAL_IN_NAME.matcher(basename(path));
			if (matcher.find()) {
				return Long.parseLong(matcher.group(1));
			}
			throw new IllegalArgumentException("could not infer the serial from '" + path + "'; pass --serial explicitly");
		}

		/** Last path segment of a path, stripped of any leading group/parent path and trailing slashes. */
		static String basename(final String path) {
			String p = path;
			while (p.endsWith("/")) {
				p = p.substring(0, p.length() - 1);
			}
			final int slash = p.lastIndexOf('/');
			return slash >= 0 ? p.substring(slash + 1) : p;
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

		// Read the tissue s0 metadata, the render target, the world->voxel offset, and discover the pyramid levels.
		final double[] worldToVoxel;
		final RenderTarget renderTarget;
		final int numDimensions;
		final List<String> levels = new ArrayList<>();
		final Map<String, DatasetAttributes> levelAttributes = new LinkedHashMap<>();
		int[] downsampleFactors = null;
		try (final N5Reader n5 = openN5Reader(params.n5Path)) {

			final DatasetAttributes s0Attributes = n5.getDatasetAttributes(params.fullDataset());
			if (s0Attributes == null) {
				throw new IllegalArgumentException("tissue dataset " + params.fullDataset() + " does not exist");
			}
			numDimensions = s0Attributes.getNumDimensions();
			if (numDimensions != 3) {
				throw new IllegalArgumentException("expected a 3D tissue volume but " + params.fullDataset() +
												   " has " + numDimensions + " dimensions");
			}
			final DatasetAttributes maskAttributes = n5.getDatasetAttributes(params.mask);
			if (maskAttributes == null) {
				throw new IllegalArgumentException("mask dataset " + params.mask + " does not exist");
			}

			// The inpainter reads and writes one chunk at a time (no whole-volume open, so no accumulating cell
			// cache). That requires z to be a single chunk, so that every z-1 / z+1 section the z-average needs lives
			// inside the block; and it reads the mask by the tissue block's grid position, so the two datasets must
			// share the same block grid and origin.
			if (s0Attributes.getBlockSize()[2] < s0Attributes.getDimensions()[2]) {
				throw new IllegalArgumentException(
						"block-local inpainting requires z to be a single chunk, but " + params.fullDataset() +
						" has blockSize[2]=" + s0Attributes.getBlockSize()[2] +
						" < dimensions[2]=" + s0Attributes.getDimensions()[2]);
			}
			if (! Arrays.equals(maskAttributes.getBlockSize(), s0Attributes.getBlockSize()) ||
				! Arrays.equals(maskAttributes.getDimensions(), s0Attributes.getDimensions())) {
				throw new IllegalArgumentException(
						"mask grid " + Arrays.toString(maskAttributes.getDimensions()) + " @ " +
						Arrays.toString(maskAttributes.getBlockSize()) + " must match tissue grid " +
						Arrays.toString(s0Attributes.getDimensions()) + " @ " +
						Arrays.toString(s0Attributes.getBlockSize()));
			}

			// Render service parameters (baseDataUrl / owner / project / stack) come straight from the group's
			// "renderExport" metadata written by render's N5 export — the same attributes.json that holds the pyramid
			// scales and translate — so they never have to be passed on the command line.
			renderTarget = readRenderTarget(n5, params.dataset);

			// World->voxel offset for placing render tile centers: the neuroglancer 'translate' (the stack
			// bounding-box min in world pixels) is written on the multiscale GROUP, not on s0 (s0 only carries a
			// sub-pixel centering transform). May be null here; loadPointCloud falls back to the render stack bounds.
			worldToVoxel = readGroupTranslate(n5, params.dataset);

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

			// The relative per-level downsampling factor is read from the pyramid itself rather than passed in: the
			// group's neuroglancer "scales" attribute lists the cumulative factor per level relative to s0, and these
			// pyramids are built with a constant factor at every step (see DownsampleHelper / N5DownsamplerSpark), so
			// scales[1] applies to all levels.
			if (levels.size() > 1) {
				downsampleFactors = readDownsamplingFactors(n5, params.dataset, numDimensions);
			}
		}
		LOG.info("run: world->voxel translate={}, pyramid levels={}, downsampleFactors={}",
				 Arrays.toString(worldToVoxel), levels, Arrays.toString(downsampleFactors));

		// Load the (small) per-slab ROI point cloud on the driver: positions from the aligned render stack, distances
		// from the xlog. A single render request keeps the server load light; the cloud is then broadcast to executors.
		final RenderDataClient renderClient =
				new RenderDataClient(renderTarget.baseDataUrl, renderTarget.owner, renderTarget.project);
		final PointCloud cloud = loadPointCloud(params, renderClient, renderTarget.stack, worldToVoxel);
		LOG.info("run: loaded {} ROI reference points for serial {} (render {} {}/{}/{})",
				 cloud.size(), params.serial, renderTarget.baseDataUrl, renderTarget.owner, renderTarget.project,
				 renderTarget.stack);

		// Create the backup container and mirror all datasets that might receive backups.
		final String backupPath = params.getBackupPath();
		if (! params.dryRun) {
			try (final N5Writer backup = openN5Writer(backupPath)) {
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
			runWithSparkContext(sparkContext, cloud, levels, levelAttributes, backupPath, downsampleFactors);
		}
	}

	private void runWithSparkContext(final JavaSparkContext sparkContext,
									 final PointCloud cloud,
									 final List<String> levels,
									 final Map<String, DatasetAttributes> levelAttributes,
									 final String backupPath,
									 final int[] downsampleFactors) {

		final DatasetAttributes s0Attributes = levelAttributes.get(params.fullDataset());
		final List<Grid.Block> s0Blocks = new ArrayList<>(Grid.create(s0Attributes.getDimensions(),
																	   s0Attributes.getBlockSize()));

		// Grid.create returns blocks in raster order, and Spark's parallelize slices the list into contiguous
		// partitions. The blocks that actually do work (present-check + inpaint) are the near-ROI ones, and the ROI is
		// a small, spatially clustered region, so contiguous slicing piles all the expensive blocks into a few
		// partitions while the rest only run the cheap no-I/O distance filter -> severe load skew. Shuffling first gives
		// every partition a representative mix of near- and far-ROI blocks, so the pass is balanced. The seed (serial)
		// keeps the partitioning reproducible, and the outputs (emitted grid positions, per-block decision logs) are
		// order-independent, so this changes only the distribution of work, not the result.
		Collections.shuffle(s0Blocks, new Random(params.serial));
		LOG.info("runWithSparkContext: {} s0 grid blocks to consider (shuffled for load balance)", s0Blocks.size());

		final Broadcast<PointCloud> cloudBroadcast = sparkContext.broadcast(cloud);
		final Broadcast<Parameters> paramsBroadcast = sparkContext.broadcast(params);

		// A single driver line carries the grid extent and z-depth the visualization needs; the per-block
		// 'blockDecision ...' lines are logged on the executors (see inpaintPartition / logDecision).
		final long[] dims = s0Attributes.getDimensions();
		final int[] blockSize = s0Attributes.getBlockSize();
		LOG.info("runWithSparkContext: diagnostics metadata serial={} maxRoiDistance={} gridX={} gridY={} zLayers={}",
				 params.serial, params.maxRoiDistance,
				 (dims[0] + blockSize[0] - 1) / blockSize[0], (dims[1] + blockSize[1] - 1) / blockSize[1], dims[2]);

		// 2. + 3. Filter (distance, then presence) and inpaint in one distributed pass.
		final String backup = params.dryRun ? null : backupPath;
		final JavaRDD<long[]> modifiedRDD = sparkContext.parallelize(s0Blocks).mapPartitions(
				blockIterator -> inpaintPartition(blockIterator,
												  cloudBroadcast.value(),
												  paramsBroadcast.value(),
												  backup));

		final List<long[]> modifiedS0 = modifiedRDD.collect();
		LOG.info("runWithSparkContext: {} s0 block(s) were {}",
				 modifiedS0.size(), params.dryRun ? "identified for inpainting (dry run)" : "inpainted");

		if (params.dryRun || modifiedS0.isEmpty()) {
			return;
		}

		// 6. Selectively update the downsample pyramid, one level at a time.
		updatePyramid(sparkContext, levels, levelAttributes, modifiedS0, backupPath, downsampleFactors);
	}

	// ------------------------------------------------------------------------------------------------
	// Step 1: load the per-slab ROI point cloud (positions from the aligned render stack, distances from the xlog).
	// ------------------------------------------------------------------------------------------------

	/**
	 * Builds the per-slab ROI point cloud in the tissue s0 <b>voxel</b> frame. Positions come from the ALIGNED render
	 * stack (so montage stitching is accounted for): the first layer's tile bounds are fetched from the render web
	 * service on the driver, and each tile center (render world pixels) is mapped to the voxel frame with
	 * {@code voxel = center - worldToVoxel} ({@code worldToVoxel} is the neuroglancer group-level {@code translate},
	 * i.e. the stack bounding-box min; if it is null we fall back to the render stack bounds). Each tile carries the
	 * {@code distance_roi} of its SFOV, read from the xlog for the slab whose {@code id_serial} equals
	 * {@code params.serial}: the tile's mfov (0-based, parsed from the tileId) indexes the xlog mfov axis directly, and
	 * its sfov number (the 1-based {@code _s##} field) maps directly to the 0-based xlog sfov axis as {@code _s## - 1}.
	 * Tiles whose SFOV has no (NaN) {@code distance_roi}, or an out-of-range mfov/sfov, are dropped.
	 */
	static PointCloud loadPointCloud(final Parameters params,
									 final RenderDataClient renderClient,
									 final String stack,
									 final double[] worldToVoxel)
			throws IOException {

		// (a) xlog: the slab's 2-D distance_roi grid, materialized into a plain array so it outlives the xlog reader.
		final double[][] distBySfovMfov; // [rowMajorSfov0Based][mfov0Based]
		try (final N5Reader xlog = new N5Factory().openReader(params.xlogPath)) {

			final double[] idSerial = read1d(xlog, "id_serial");
			final int slabPosition = findSlabPosition(idSerial, params.serial);
			if (slabPosition < 0) {
				final long[] available = new long[idSerial.length];
				for (int i = 0; i < idSerial.length; i++) {
					available[i] = Math.round(idSerial[i]);
				}
				throw new IllegalArgumentException("serial " + params.serial + " not found in id_serial; available serials are " +
												   Arrays.toString(available));
			}

			final RandomAccessibleInterval<DoubleType> distAll = openDoubles(xlog, "distance_roi");
			requireSlabAxis(distAll, DIST_AXIS_SLAB, idSerial.length, "distance_roi");

			// distance_roi is scan-independent: slice the slab axis -> 2-D (sfov, mfov).
			final RandomAccessibleInterval<DoubleType> distSlab = Views.hyperSlice(distAll, DIST_AXIS_SLAB, slabPosition);
			final int nSfov = (int) distSlab.dimension(0);
			final int nMfov = (int) distSlab.dimension(1);
			distBySfovMfov = new double[nSfov][nMfov];
			final RandomAccess<DoubleType> dra = distSlab.randomAccess();
			final long[] pos = new long[2];
			for (int s = 0; s < nSfov; s++) {
				for (int m = 0; m < nMfov; m++) {
					pos[0] = s;
					pos[1] = m;
					distBySfovMfov[s][m] = dra.setPositionAndGet(pos).get();
				}
			}
			LOG.info("loadPointCloud: serial {} -> slab position {}, distance_roi grid is {} sfov x {} mfov",
					 params.serial, slabPosition, nSfov, nMfov);
		}
		final int nSfov = distBySfovMfov.length;
		final int nMfov = nSfov > 0 ? distBySfovMfov[0].length : 0;

		// (b) render: the first layer's aligned tile centers, fetched once on the driver.
		final List<Double> zValues = renderClient.getStackZValues(stack);
		if (zValues.isEmpty()) {
			throw new IllegalArgumentException("render stack " + stack + " has no z layers");
		}
		final double firstZ = zValues.get(0);
		final List<TileBounds> tiles = renderClient.getTileBounds(stack, firstZ);

		final double offsetX;
		final double offsetY;
		if (worldToVoxel != null) {
			offsetX = worldToVoxel[0];
			offsetY = worldToVoxel[1];
		} else {
			final Bounds stackBounds = renderClient.getStackMetaData(stack).getStackBounds();
			if (stackBounds == null || stackBounds.getMinX() == null || stackBounds.getMinY() == null) {
				throw new IllegalArgumentException("N5 group " + params.dataset + " has no neuroglancer 'translate' and " +
												   "render stack " + stack + " has no bounds; cannot map world coordinates to voxels");
			}
			offsetX = stackBounds.getMinX();
			offsetY = stackBounds.getMinY();
			LOG.warn("loadPointCloud: N5 group {} has no 'translate'; using render stack bounds min ({}, {}) as the world->voxel offset",
					 params.dataset, offsetX, offsetY);
		}

		// Attach each tile's distance_roi (by mfov + sfov) and place its center in the voxel frame.
		final List<Double> xs = new ArrayList<>();
		final List<Double> ys = new ArrayList<>();
		final List<Double> ds = new ArrayList<>();
		int noDistance = 0;
		int outOfRange = 0;
		for (final TileBounds tile : tiles) {
			final String tileId = tile.getTileId();
			final int mfov = Integer.parseInt(MultiSemUtilities.getSimpleMfovForTileId(tileId).substring(1)); // m0013 -> 13
			final int sfov = Integer.parseInt(MultiSemUtilities.getSFOVIndexForTileId(tileId)) - 1;
			if (mfov < 0 || mfov >= nMfov || sfov < 0 || sfov >= nSfov) {
				outOfRange++;
				continue;
			}
			final double d = distBySfovMfov[sfov][mfov];
			if (! Double.isFinite(d)) {
				noDistance++;
				continue;
			}
			xs.add(tile.getCenterX() - offsetX);
			ys.add(tile.getCenterY() - offsetY);
			ds.add(d);
		}
		if (xs.isEmpty()) {
			throw new IllegalArgumentException("no render tiles in stack " + stack + " z " + firstZ +
											   " could be matched to a finite xlog distance_roi (fetched " + tiles.size() +
											   " tiles; " + noDistance + " had NaN distance, " + outOfRange + " had out-of-range mfov/sfov)");
		}
		LOG.info("loadPointCloud: matched {} of {} render tiles (z {}) to distance_roi; dropped {} NaN-distance, {} out-of-range; world->voxel offset ({}, {})",
				 xs.size(), tiles.size(), firstZ, noDistance, outOfRange, offsetX, offsetY);
		return new PointCloud(toArray(xs), toArray(ys), toArray(ds));
	}

	/** Fails fast if a hardcoded slab axis does not carry the expected slab count (guards the layout at the top). */
	private static void requireSlabAxis(final RandomAccessibleInterval<?> img, final int axis, final long slabCount,
										final String field) {
		if (img.numDimensions() <= axis || img.dimension(axis) != slabCount) {
			throw new IllegalArgumentException("xlog field '" + field + "' has dimensions " +
											   Arrays.toString(img.dimensionsAsLongArray()) + " but the hardcoded layout " +
											   "expects " + slabCount + " slabs on axis " + axis +
											   " (see the xlog axis layout at the top of the class)");
		}
	}

	/** Copies a list of doubles into a primitive array. */
	private static double[] toArray(final List<Double> list) {
		final double[] array = new double[list.size()];
		for (int i = 0; i < array.length; i++) {
			array[i] = list.get(i);
		}
		return array;
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
													 final String backupPath) {

		LogUtilities.setupExecutorLog4j("inpaint");

		final List<long[]> modified = new ArrayList<>();
		final KNearestNeighborSearchOnKDTree<DoubleType> search = cloud.buildSearch(IDW_K);

		try (final N5Reader reader = openN5Reader(params.n5Path);
			 final N5Writer tissueWriter = params.dryRun ? null : openN5Writer(params.n5Path);
			 final N5Writer backupWriter = params.dryRun ? null : openN5Writer(backupPath)) {

			final DatasetAttributes s0Attributes = reader.getDatasetAttributes(params.fullDataset());
			final DatasetAttributes maskAttributes = reader.getDatasetAttributes(params.mask);

			long considered = 0;
			long nearRoi = 0;
			long present = 0;
			while (blocks.hasNext()) {
				final Grid.Block block = blocks.next();
				considered++;
				final long gridX = block.gridPosition[0];
				final long gridY = block.gridPosition[1];

				// (1) ROI-distance filter, computed with no I/O. The cloud is already in the s0 voxel frame, so the
				// block center (voxel index) queries it directly; distance_roi is interpolated by inverse-distance
				// weighting (the interpolated value is in microns regardless of the voxel-space query units).
				final double centerX = block.offset[0] + block.dimensions[0] / 2.0;
				final double centerY = block.offset[1] + block.dimensions[1] / 2.0;
				final double roiDistance = cloud.interpolate(search, centerX, centerY, IDW_POWER);
				if (! (roiDistance < params.maxRoiDistance)) {
					logDecision(gridX, gridY, "outside_roi", -1, roiDistance);
					continue;
				}
				nearRoi++;

				// (2) presence check: readBlock returns null for absent (empty) blocks. The block it returns is also
				// the raw tissue we inpaint from and back up, so no whole-volume open (and no accumulating cell cache)
				// is needed: because z is a single chunk (guarded on the driver), every z-1 / z+1 section the
				// z-average reads lives inside this same block. Near-ROI empty blocks are not logged: the distance
				// filter runs before this presence check, so block emptiness is not observable outside the ROI.
				final DataBlock<?> tissueBlock = reader.readBlock(params.fullDataset(), s0Attributes, block.gridPosition);
				if (tissueBlock == null) {
					continue;
				}
				present++;

				// (3) inpaint: fill mask==0 pixels with the z-average of the sections above/below. The mask is read by
				// the same grid position (the datasets share a grid, guarded on the driver); an absent mask block
				// counts as all-background (all holes), matching a zero-filled whole-volume read of a missing chunk.
				final DataBlock<?> maskBlock = reader.readBlock(params.mask, maskAttributes, block.gridPosition);
				final InpaintResult result = inpaintBlock(asByteImg(tissueBlock, block.dimensions),
														  asByteImg(maskBlock, block.dimensions),
														  block.dimensions);
				if (! result.changed) {
					logDecision(gridX, gridY, "inside_roi", -1, roiDistance);
					continue;
				}

				modified.add(block.gridPosition);
				if (! params.dryRun) {
					backupWriter.writeBlock(params.fullDataset(), s0Attributes, tissueBlock);
					N5Utils.saveBlock(result.inpainted, tissueWriter, params.fullDataset(), s0Attributes, block.gridPosition);
				}
				logDecision(gridX, gridY, "inpainted", result.minChangedLayer, roiDistance);
			}
			LOG.info("inpaintPartition: partition summary: considered={}, nearRoi(<{}um)={}, present={}, inpainted={}",
					 considered, params.maxRoiDistance, nearRoi, present, modified.size());
		}

		return modified.iterator();
	}

	/**
	 * Logs one greppable per-block decision line consumed by the visualization (plot_inpainter_diagnostics.py). Keeping
	 * a fixed {@code blockDecision key=value ...} shape means the log is the single source of truth — no file is written
	 * and no decision logic is re-derived downstream.
	 */
	private static void logDecision(final long gridX, final long gridY, final String decision,
									final int minLayer, final double roiDistance) {
		LOG.info("blockDecision gridX={} gridY={} decision={} minLayer={} roiDistance={}",
				 gridX, gridY, decision, minLayer, roiDistance);
	}

	/**
	 * Wraps a uint8 {@link DataBlock} as a block-local image. An absent (null) block becomes all-zeros, matching the
	 * zero fill an {@code N5Utils.open} whole-volume read would give for a missing chunk.
	 */
	private static Img<UnsignedByteType> asByteImg(final DataBlock<?> dataBlock, final long[] blockDimensions) {
		if (dataBlock == null) {
			return ArrayImgs.unsignedBytes(blockDimensions);
		}
		return ArrayImgs.unsignedBytes((byte[]) dataBlock.getData(), blockDimensions);
	}

	/**
	 * Produces the inpainted version of a single block: every pixel where the mask is 0 is replaced with the average
	 * of the tissue in the sections above and below (the single neighbor is copied at the block's z-boundary). Both
	 * {@code tissueBlock} and {@code maskBlock} are block-local images with dimensions {@code blockDimensions}; because
	 * the volume is a single z-chunk, the block's z-boundary is the volume's z-boundary, so no neighbouring block is
	 * needed for the z-average.
	 */
	static InpaintResult inpaintBlock(final RandomAccessibleInterval<UnsignedByteType> tissueBlock,
									  final RandomAccessibleInterval<UnsignedByteType> maskBlock,
									  final long[] blockDimensions) {

		final RandomAccess<UnsignedByteType> tissueAccess = tissueBlock.randomAccess();
		final RandomAccess<UnsignedByteType> maskAccess = maskBlock.randomAccess();
		final long zMax = blockDimensions[2] - 1;

		final Img<UnsignedByteType> inpainted = ArrayImgs.unsignedBytes(blockDimensions);
		final Cursor<UnsignedByteType> cursor = inpainted.localizingCursor();
		final long[] local = new long[3];
		boolean changed = false;
		int minChangedLayer = Integer.MAX_VALUE;
		while (cursor.hasNext()) {
			final UnsignedByteType target = cursor.next();
			cursor.localize(local);

			final int original = tissueAccess.setPositionAndGet(local).get();
			final int value;
			if (maskAccess.setPositionAndGet(local).get() > 0) {
				value = original;
			} else {
				value = zAverage(tissueAccess, local, 0, zMax);
				if (value != original) {
					changed = true;
					if (local[2] < minChangedLayer) {
						minChangedLayer = (int) local[2];
					}
				}
			}
			target.set(value);
		}

		return new InpaintResult(inpainted, changed, changed ? minChangedLayer : -1);
	}

	/** Average of the tissue in the z-1 and z+1 sections, copying the single neighbor at the volume z-boundary. */
	private static int zAverage(final RandomAccess<UnsignedByteType> tissueAccess,
								final long[] world,
								final long zMin,
								final long zMax) {
		final long z = world[2];
		int sum = 0;
		int count = 0;
		if (z - 1 >= zMin) {
			world[2] = z - 1;
			sum += tissueAccess.setPositionAndGet(world).get();
			count++;
		}
		if (z + 1 <= zMax) {
			world[2] = z + 1;
			sum += tissueAccess.setPositionAndGet(world).get();
			count++;
		}
		world[2] = z;
		// both neighbors -> mean (== the old (above+below)>>>1 for byte values); one -> that neighbor; none -> unchanged.
		return count > 0 ? sum / count : tissueAccess.setPositionAndGet(world).get();
	}

	// ------------------------------------------------------------------------------------------------
	// Step 6: selectively re-downsample only the pyramid blocks affected by the modified s0 blocks.
	// ------------------------------------------------------------------------------------------------

	private void updatePyramid(final JavaSparkContext sparkContext,
							   final List<String> levels,
							   final Map<String, DatasetAttributes> levelAttributes,
							   final List<long[]> modifiedS0,
							   final String backupPath,
							   final int[] factors) {

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
			// Every affected block does real work, so there is no near/far skew here, but the per-block cost still varies
			// with how much of its source region is actually present (dense in the ROI interior, sparse at its edge).
			// The affected blocks inherit a spatial order, so contiguous partitioning would group same-density
			// neighbours together and leave some partitions all-dense and others all-sparse. Shuffle (as for s0) mixes
			// densities across partitions; the seed varies per level but stays reproducible.
			Collections.shuffle(affectedBlocks, new Random(params.serial * 31L + scale));
			LOG.info("updatePyramid: re-downsampling {} block(s) for {}", affectedBlocks.size(), toDataset);

			final String n5Path = params.n5Path;
			sparkContext.parallelize(affectedBlocks).foreachPartition(
					gridPositions -> downsamplePartition(gridPositions, n5Path, backupPath,
														 fromDataset, toDataset, toAttributes, factors));

			modifiedPrevious = affectedBlocks;
		}
	}

	/**
	 * Re-downsamples one partition's worth of pyramid blocks, opening the N5 handles and the source level <b>once</b>
	 * for the whole partition rather than per block (the source open is a lazy {@code N5Utils.open}, so each block
	 * still reads only the source chunks it needs). Each block is rebuilt from the (already updated) previous level
	 * with the same per-block math as {@code N5DownsamplerSpark} so the result matches the rest of the pyramid, backing
	 * up the original block before overwriting it.
	 */
	static void downsamplePartition(final Iterator<long[]> gridPositions,
									final String n5Path,
									final String backupPath,
									final String fromDataset,
									final String toDataset,
									final DatasetAttributes toAttributes,
									final int[] factors) {

		LogUtilities.setupExecutorLog4j("downsample");

		final CellGrid cellGrid = new CellGrid(toAttributes.getDimensions(), toAttributes.getBlockSize());

		try (final N5Reader reader = openN5Reader(n5Path);
			 final N5Writer writer = openN5Writer(n5Path);
			 final N5Writer backupWriter = openN5Writer(backupPath)) {

			final RandomAccessibleInterval<UnsignedByteType> source = N5Utils.open(reader, fromDataset);

			int count = 0;
			while (gridPositions.hasNext()) {
				downsampleBlock(source, reader, writer, backupWriter, cellGrid,
								gridPositions.next(), toDataset, toAttributes, factors);
				count++;
			}
			LOG.info("downsamplePartition: re-downsampled {} {} block(s)", count, toDataset);
		}
	}

	/** Re-downsamples a single block using the handles and source level already opened for the partition. */
	private static void downsampleBlock(final RandomAccessibleInterval<UnsignedByteType> source,
										final N5Reader reader,
										final N5Writer writer,
										final N5Writer backupWriter,
										final CellGrid cellGrid,
										final long[] gridPosition,
										final String toDataset,
										final DatasetAttributes toAttributes,
										final int[] factors) {

		final int n = toAttributes.getNumDimensions();
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

	/**
	 * Reads the neuroglancer {@code translate} (the stack bounding-box min in world pixels) from the multiscale group,
	 * or returns null if it is absent. The render N5 export writes it on the group ({@code <dataset>}), not on
	 * {@code s0} (s0 only carries a sub-pixel centering {@code transform}); callers fall back to the render stack bounds.
	 */
	private static double[] readGroupTranslate(final N5Reader n5, final String group) {
		try {
			final double[] translate = n5.getAttribute(group, "translate", double[].class);
			if (translate != null && translate.length >= 2) {
				return translate;
			}
		} catch (final Exception e) {
			LOG.warn("readGroupTranslate: could not read 'translate' from group {} ({})", group, e.getMessage());
		}
		return null;
	}

	/** Opens an N5 reader for a tissue/backup container (explicit N5 format; local path or gs://). */
	private static N5Reader openN5Reader(final String path) {
		return new N5Factory().openReader(N5Factory.StorageFormat.N5, path);
	}

	/** Opens an N5 writer for a tissue/backup container (explicit N5 format; local path or gs://). */
	private static N5Writer openN5Writer(final String path) {
		return new N5Factory().openWriter(N5Factory.StorageFormat.N5, path);
	}

	/**
	 * Reads the per-step pyramid downsampling factor from the multiscale group's neuroglancer {@code scales} attribute
	 * (as written by render's N5 export). {@code scales} is the cumulative factor per level relative to s0, e.g.
	 * {@code [[1,1,1],[2,2,1],[4,4,1],...]}, so {@code scales[1]} is the factor of s1 relative to s0 — and because these
	 * pyramids use a constant step at every level, it is the per-step factor for all levels. Fails fast when it is
	 * absent or has fewer than two levels, so the pyramid factor is never guessed.
	 */
	private static int[] readDownsamplingFactors(final N5Reader n5, final String group, final int numDimensions) {
		final int[][] scales = n5.getAttribute(group, "scales", int[][].class);
		if (scales == null || scales.length < 2) {
			throw new IllegalArgumentException("group " + group + " has no 'scales' attribute with at least two levels " +
											   "to derive the pyramid downsampling factor from");
		}
		final int[] factors = scales[1]; // s1 relative to s0 == the per-step factor (constant-step pyramids)
		if (factors.length != numDimensions) {
			throw new IllegalArgumentException("group " + group + " has scales[1] " + Arrays.toString(factors) +
											   " but the volume is " + numDimensions + "-dimensional");
		}
		return factors;
	}

	/**
	 * Reads the render service coordinates (baseDataUrl / owner / project / stack) straight from the multiscale group's
	 * {@code renderExport} metadata, written by render's N5 export (the same attributes.json that holds the pyramid
	 * {@code scales} and {@code translate}). Fails fast when it is absent so the render target is never guessed.
	 */
	private static RenderTarget readRenderTarget(final N5Reader n5, final String group) {
		final RenderExport export = n5.getAttribute(group, "renderExport", RenderExport.class);
		if (export == null || export.runParameters == null || export.runParameters.renderWeb == null ||
			export.runParameters.renderWeb.baseDataUrl == null || export.runParameters.stack == null) {
			throw new IllegalArgumentException(
					"N5 group " + group + " has no usable 'renderExport' metadata (need runParameters.renderWeb." +
					"baseDataUrl/owner/project and runParameters.stack); this client reads the render service parameters from there");
		}
		final RenderExport.RenderWeb web = export.runParameters.renderWeb;
		return new RenderTarget(web.baseDataUrl, web.owner, web.project, export.runParameters.stack);
	}

	/** Render service coordinates resolved from the group's {@code renderExport} metadata. */
	static class RenderTarget {
		final String baseDataUrl;
		final String owner;
		final String project;
		final String stack;

		RenderTarget(final String baseDataUrl, final String owner, final String project, final String stack) {
			this.baseDataUrl = baseDataUrl;
			this.owner = owner;
			this.project = project;
			this.stack = stack;
		}
	}

	/**
	 * Minimal GSON view of the group's {@code renderExport} attribute (only the render coordinates this client needs;
	 * all other fields written by the export are ignored).
	 */
	private static class RenderExport {
		RunParameters runParameters;

		private static class RunParameters {
			RenderWeb renderWeb;
			String stack;
		}

		private static class RenderWeb {
			String baseDataUrl;
			String owner;
			String project;
		}
	}

	/** Result of inpainting one block: the (block-sized) inpainted image and whether any pixel changed. */
	static class InpaintResult {
		final Img<UnsignedByteType> inpainted;
		final boolean changed;
		final int minChangedLayer; // minimal z-layer with an inpainted (changed) pixel, or -1 when nothing changed

		InpaintResult(final Img<UnsignedByteType> inpainted, final boolean changed, final int minChangedLayer) {
			this.inpainted = inpainted;
			this.changed = changed;
			this.minChangedLayer = minChangedLayer;
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
