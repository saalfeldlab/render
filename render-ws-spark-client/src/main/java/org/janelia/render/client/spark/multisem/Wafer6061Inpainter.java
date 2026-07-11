package org.janelia.render.client.spark.multisem;


import com.beust.jcommander.Parameter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
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
 * present ({@code mask > 0}) vs. missing ({@code mask == 0}), and (c) the acquisition xlog zarr. The ROI point cloud
 * is built in the tissue's s0 voxel frame directly from the xlog: each SFOV's acquisition position ({@code x},
 * {@code y}) is placed exactly as {@code msem_to_render.py} ingests it (rotate by {@code 180 + rotation_slab}, then
 * {@code stage - min + halfSFOV}), and carries that SFOV's {@code distance_roi}. No render service and no coordinate
 * fitting are needed (x/y/distance_roi share the same {@code (mfov, sfov)} indexing).
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
				names = "--sfovWidth",
				description = "SFOV image width in pixels (xlog X_SFOV size); used for the ingestion half-SFOV placement offset.")
		public int sfovWidth = 2000;

		@Parameter(
				names = "--sfovHeight",
				description = "SFOV image height in pixels (xlog Y_SFOV size); used for the ingestion half-SFOV placement offset.")
		public int sfovHeight = 1748;

		@Parameter(
				names = "--scan",
				description = "xlog scan index whose x/y positions define the cloud. Default (-1) auto-picks the first scan " +
							  "with finite x and rotation_slab for the slab (positions are nearly scan-independent).")
		public int scan = -1;

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

		@Parameter(
				names = "--diagnosticsPath",
				description = "Optional CSV file for per-block decisions (outside_roi / inside_roi / inpainted, plus the " +
							  "minimal inpainted z-layer). When set, decisions for ALL blocks are collected. Near-ROI blocks " +
							  "with no tissue are omitted (the distance filter runs before any presence check, so block " +
							  "emptiness is not observable outside the ROI and is not recorded as a category).")
		public String diagnosticsPath;

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
			if (sfovWidth <= 0 || sfovHeight <= 0) {
				throw new IllegalArgumentException("--sfovWidth and --sfovHeight must be positive");
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

		// 1. Load the (small) per-slab 2-D point cloud from the xlog on the driver, placed in the tissue voxel frame.
		final PointCloud cloud = loadPointCloud(params.xlogPath, params.serial,
												params.scan, params.sfovWidth, params.sfovHeight);
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

			tissueTranslate = readTranslate(n5, params.fullDataset(), numDimensions);
			maskTranslate = readTranslate(n5, params.mask, numDimensions);
			if (! Arrays.equals(tissueTranslate, maskTranslate)) {
				throw new IllegalArgumentException("tissue translate " + Arrays.toString(tissueTranslate) +
												   " must match mask translate " + Arrays.toString(maskTranslate));
			}

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
			runWithSparkContext(sparkContext, cloud, levels, levelAttributes, backupPath);
		}
	}

	private void runWithSparkContext(final JavaSparkContext sparkContext,
									 final PointCloud cloud,
									 final List<String> levels,
									 final Map<String, DatasetAttributes> levelAttributes,
									 final String backupPath) {

		final DatasetAttributes s0Attributes = levelAttributes.get(params.fullDataset());
		final List<Grid.Block> s0Blocks = Grid.create(s0Attributes.getDimensions(), s0Attributes.getBlockSize());
		LOG.info("runWithSparkContext: {} s0 grid blocks to consider", s0Blocks.size());

		final Broadcast<PointCloud> cloudBroadcast = sparkContext.broadcast(cloud);
		final Broadcast<Parameters> paramsBroadcast = sparkContext.broadcast(params);

		// 2. + 3. Filter (distance, then presence) and inpaint in one distributed pass. When a diagnostics file is
		// requested, every block's decision is collected (not just the inpainted ones) so the full picture can be drawn.
		final String backup = params.dryRun ? null : backupPath;
		final boolean emitAllDecisions = params.diagnosticsPath != null;
		final JavaRDD<BlockDecision> decisionRDD = sparkContext.parallelize(s0Blocks).mapPartitions(
				blockIterator -> inpaintPartition(blockIterator,
												  cloudBroadcast.value(),
												  paramsBroadcast.value(),
												  backup,
												  emitAllDecisions));

		final List<BlockDecision> decisions = decisionRDD.collect();

		final List<long[]> modifiedS0 = new ArrayList<>();
		long outsideCount = 0;
		long insideCount = 0;
		for (final BlockDecision d : decisions) {
			switch (d.decision) {
				case DECISION_OUTSIDE_ROI: outsideCount++; break;
				case DECISION_INSIDE_ROI:  insideCount++; break;
				case DECISION_INPAINTED:   modifiedS0.add(new long[] {d.gridX, d.gridY, 0}); break;
				default: break;
			}
		}
		LOG.info("runWithSparkContext: {} s0 block(s) were {}",
				 modifiedS0.size(), params.dryRun ? "identified for inpainting (dry run)" : "inpainted");
		if (emitAllDecisions) {
			LOG.info("runWithSparkContext: decision counts: outside_roi={}, inside_roi={}, inpainted={}",
					 outsideCount, insideCount, modifiedS0.size());
			writeDiagnostics(params.diagnosticsPath, decisions, s0Attributes);
		}

		if (params.dryRun || modifiedS0.isEmpty()) {
			return;
		}

		// 6. Selectively update the downsample pyramid, one level at a time.
		updatePyramid(sparkContext, levels, levelAttributes, modifiedS0, backupPath);
	}

	/**
	 * Writes one CSV row per recorded {@link BlockDecision} plus a small metadata header. Consumed as-is by the
	 * visualization; the client is the single source of truth for the decisions (no logic is re-derived downstream).
	 */
	private void writeDiagnostics(final String diagnosticsPath,
								  final List<BlockDecision> decisions,
								  final DatasetAttributes s0Attributes) {
		final long[] dims = s0Attributes.getDimensions();
		final int[] blockSize = s0Attributes.getBlockSize();
		final long gridX = (dims[0] + blockSize[0] - 1) / blockSize[0];
		final long gridY = (dims[1] + blockSize[1] - 1) / blockSize[1];
		try (final BufferedWriter writer = Files.newBufferedWriter(Paths.get(diagnosticsPath))) {
			writer.write("# Wafer6061Inpainter diagnostics\n");
			writer.write("# serial=" + params.serial + " maxRoiDistance=" + params.maxRoiDistance +
						 " gridX=" + gridX + " gridY=" + gridY + " zLayers=" + dims[2] + "\n");
			writer.write("gridX,gridY,roiDistance,decision,minLayer\n");
			for (final BlockDecision d : decisions) {
				writer.write(d.gridX + "," + d.gridY + "," + Double.toString(d.roiDistance) + "," +
							 decisionLabel(d.decision) + "," + d.minLayer + "\n");
			}
		} catch (final IOException e) {
			throw new RuntimeException("failed to write diagnostics to " + diagnosticsPath, e);
		}
		LOG.info("writeDiagnostics: wrote {} block decisions to {}", decisions.size(), diagnosticsPath);
	}

	// ------------------------------------------------------------------------------------------------
	// Step 1: load the per-slab 2-D point cloud from the xlog zarr.
	// ------------------------------------------------------------------------------------------------

	/**
	 * Builds the per-slab ROI point cloud in the tissue s0 <b>voxel</b> frame, entirely from the xlog. For the slab
	 * whose {@code id_serial} equals {@code serial}, each SFOV's acquisition position ({@code x}, {@code y}, in
	 * full-resolution pixels) is placed into the render/ingestion frame exactly as {@code msem_to_render.py} does:
	 * <pre>
	 *   center = EuclideanTransform(rotation = radians(180 + rotation_slab)) . (x, y)
	 *   voxel  = center - min(center) + (sfovWidth/2, sfovHeight/2)
	 * </pre>
	 * i.e. the ingestion {@code stage - min + margin} placement (the constant {@code margin} and the export
	 * {@code translate} cancel out into the voxel frame). Each voxel carries the scan-independent {@code distance_roi}
	 * for that SFOV. Since {@code x}, {@code y} and {@code distance_roi} are all indexed by {@code (mfov, sfov)} in the
	 * xlog, the correspondence is exact and no fitting is needed. Alignment (montage stitching) only perturbs these
	 * positions slightly, well within {@code maxRoiDistance}, so the unaligned ingestion placement is used directly.
	 */
	static PointCloud loadPointCloud(final String xlogPath,
									 final long serial,
									 final int scanOverride,
									 final int sfovWidth,
									 final int sfovHeight) {
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
			final long slabCount = idSerial.length; // 413 for wafer 61

			// SFOV image size defines the half-SFOV placement offset; prefer the xlog (x_sfov / y_sfov), fall back to args.
			final int sfW = sfovSize(xlog, "x_sfov", sfovWidth);
			final int sfH = sfovSize(xlog, "y_sfov", sfovHeight);

			// distance_roi is scan-independent: [slab, mfov, sfov] -> 2-D (sfov, mfov) after slicing the slab axis.
			final RandomAccessibleInterval<DoubleType> distSlab = readSlab(xlog, "distance_roi", slabCount, slabPosition);

			// x / y are [scan, slab, mfov, sfov]; rotation_slab is [scan, slab]. Identify axes by their (unique) sizes.
			final RandomAccessibleInterval<DoubleType> xAll = openDoubles(xlog, "x");
			final RandomAccessibleInterval<DoubleType> yAll = openDoubles(xlog, "y");
			final RandomAccessibleInterval<DoubleType> rotAll = openDoubles(xlog, "rotation_slab");
			final int rotSlabAxis = axisOfSize(rotAll, slabCount, "rotation_slab");
			final int rotScanAxis = 1 - rotSlabAxis;
			final long nScans = rotAll.dimension(rotScanAxis);
			final int slabAxisX = axisOfSize(xAll, slabCount, "x");
			final int scanAxisX = axisOfSize(xAll, nScans, "x");

			// Choose the scan whose x/y define the cloud (positions are nearly scan-independent).
			int scan = scanOverride;
			if (scan < 0) {
				for (int s = 0; s < nScans; s++) {
					if (! Double.isFinite(rotationAt(rotAll, rotSlabAxis, rotScanAxis, slabPosition, s))) {
						continue;
					}
					if (hasFinite(sliceScanAndSlab(xAll, scanAxisX, s, slabAxisX, slabPosition))) {
						scan = s;
						break;
					}
				}
				if (scan < 0) {
					throw new IllegalArgumentException("no scan with finite x and rotation_slab found for serial " +
													   serial + " (slab position " + slabPosition + ")");
				}
			}

			final double rotationSlab = rotationAt(rotAll, rotSlabAxis, rotScanAxis, slabPosition, scan);
			final double theta = Math.toRadians(180.0 + rotationSlab);
			final double cos = Math.cos(theta);
			final double sin = Math.sin(theta);

			final RandomAccessibleInterval<DoubleType> xSlab = sliceScanAndSlab(xAll, scanAxisX, scan, slabAxisX, slabPosition);
			final RandomAccessibleInterval<DoubleType> ySlab = sliceScanAndSlab(yAll, scanAxisX, scan, slabAxisX, slabPosition);

			// Place each SFOV center (rotation only) and keep the finite ones with their distance_roi.
			final List<Double> cxs = new ArrayList<>();
			final List<Double> cys = new ArrayList<>();
			final List<Double> dists = new ArrayList<>();
			final RandomAccess<DoubleType> xra = xSlab.randomAccess();
			final RandomAccess<DoubleType> yra = ySlab.randomAccess();
			final RandomAccess<DoubleType> dra = distSlab.randomAccess();
			final long[] pos = new long[2];
			double minX = Double.POSITIVE_INFINITY;
			double minY = Double.POSITIVE_INFINITY;
			for (long i0 = 0; i0 < xSlab.dimension(0); i0++) {
				for (long i1 = 0; i1 < xSlab.dimension(1); i1++) {
					pos[0] = i0;
					pos[1] = i1;
					final double x = xra.setPositionAndGet(pos).get();
					final double y = yra.setPositionAndGet(pos).get();
					final double d = dra.setPositionAndGet(pos).get();
					if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(d)) {
						final double cx = cos * x - sin * y;
						final double cy = sin * x + cos * y;
						cxs.add(cx);
						cys.add(cy);
						dists.add(d);
						minX = Math.min(minX, cx);
						minY = Math.min(minY, cy);
					}
				}
			}
			if (cxs.isEmpty()) {
				throw new IllegalArgumentException("no finite ROI points found for serial " + serial +
												   " (slab position " + slabPosition + ", scan " + scan + ")");
			}

			// Shift into the voxel frame: min(center) -> half-SFOV (so the min tile's top-left is at the origin).
			final double[] xs = new double[cxs.size()];
			final double[] ys = new double[cys.size()];
			final double[] ds = new double[dists.size()];
			for (int i = 0; i < xs.length; i++) {
				xs[i] = cxs.get(i) - minX + sfW / 2.0;
				ys[i] = cys.get(i) - minY + sfH / 2.0;
				ds[i] = dists.get(i);
			}
			LOG.info("loadPointCloud: serial {} -> slab position {}, scan {}, rotation_slab {} deg, sfov {}x{}, {} points",
					 serial, slabPosition, scan, rotationSlab, sfW, sfH, xs.length);
			return new PointCloud(xs, ys, ds);
		}
	}

	/** SFOV image size from the xlog {@code x_sfov}/{@code y_sfov} length, or {@code fallback} if that dataset is absent. */
	private static int sfovSize(final N5Reader xlog, final String dataset, final int fallback) {
		try {
			final DatasetAttributes attributes = xlog.getDatasetAttributes(dataset);
			if (attributes != null && attributes.getNumDimensions() >= 1) {
				return (int) attributes.getDimensions()[0];
			}
		} catch (final Exception e) {
			LOG.warn("sfovSize: could not read {} ({}), using fallback {}", dataset, e.getMessage(), fallback);
		}
		return fallback;
	}

	/** Index of the (unique-sized) axis matching {@code size}. */
	private static int axisOfSize(final RandomAccessibleInterval<?> img, final long size, final String name) {
		for (int d = 0; d < img.numDimensions(); d++) {
			if (img.dimension(d) == size) {
				return d;
			}
		}
		throw new IllegalArgumentException("no axis of size " + size + " in " + name + " with dimensions " +
										   Arrays.toString(img.dimensionsAsLongArray()));
	}

	/** Value of the 2-D {@code rotation_slab} at the given slab position and scan. */
	private static double rotationAt(final RandomAccessibleInterval<DoubleType> rotAll,
									 final int slabAxis,
									 final int scanAxis,
									 final int slabPosition,
									 final int scan) {
		final long[] p = new long[2];
		p[slabAxis] = slabPosition;
		p[scanAxis] = scan;
		return rotAll.randomAccess().setPositionAndGet(p).get();
	}

	/** Slices the 4-D {@code x}/{@code y} array to the 2-D (sfov, mfov) plane for one scan and slab. */
	private static RandomAccessibleInterval<DoubleType> sliceScanAndSlab(final RandomAccessibleInterval<DoubleType> img,
																		 final int scanAxis,
																		 final long scan,
																		 final int slabAxis,
																		 final long slabPosition) {
		// hyper-slice the higher-indexed axis first so the lower index stays valid afterwards
		final int hi = Math.max(scanAxis, slabAxis);
		final int lo = Math.min(scanAxis, slabAxis);
		final long hiPos = (hi == scanAxis) ? scan : slabPosition;
		final long loPos = (lo == scanAxis) ? scan : slabPosition;
		return Views.hyperSlice(Views.hyperSlice(img, hi, hiPos), lo, loPos);
	}

	/** True if the interval has at least one finite value. */
	private static boolean hasFinite(final RandomAccessibleInterval<DoubleType> img) {
		for (final DoubleType t : Views.iterable(img)) {
			if (Double.isFinite(t.get())) {
				return true;
			}
		}
		return false;
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

	private static Iterator<BlockDecision> inpaintPartition(final Iterator<Grid.Block> blocks,
															final PointCloud cloud,
															final Parameters params,
															final String backupPath,
															final boolean emitAllDecisions) {

		LogUtilities.setupExecutorLog4j("inpaint");

		final List<BlockDecision> decisions = new ArrayList<>();
		final KNearestNeighborSearchOnKDTree<DoubleType> search = cloud.buildSearch(params.k);

		try (final N5Reader reader = new N5Factory().openReader(N5Factory.StorageFormat.N5, params.n5Path);
			 final N5Writer tissueWriter = params.dryRun ? null :
					 new N5Factory().openWriter(N5Factory.StorageFormat.N5, params.n5Path);
			 final N5Writer backupWriter = params.dryRun ? null :
					 new N5Factory().openWriter(N5Factory.StorageFormat.N5, backupPath)) {

			final DatasetAttributes s0Attributes = reader.getDatasetAttributes(params.fullDataset());
			final DatasetAttributes maskAttributes = reader.getDatasetAttributes(params.mask);

			long considered = 0;
			long nearRoi = 0;
			long present = 0;
			long inpainted = 0;
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
				final double roiDistance = cloud.interpolate(search, centerX, centerY, params.idwPower);
				if (! (roiDistance < params.maxRoiDistance)) {
					if (emitAllDecisions) {
						decisions.add(new BlockDecision(gridX, gridY, roiDistance, DECISION_OUTSIDE_ROI, -1));
					}
					continue;
				}
				nearRoi++;

				// (2) presence check: readBlock returns null for absent (empty) blocks. The block it returns is also
				// the raw tissue we inpaint from and back up, so no whole-volume open (and no accumulating cell cache)
				// is needed: because z is a single chunk (guarded on the driver), every z-1 / z+1 section the
				// z-average reads lives inside this same block. Near-ROI empty blocks are not recorded (see BlockDecision).
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
					if (emitAllDecisions) {
						decisions.add(new BlockDecision(gridX, gridY, roiDistance, DECISION_INSIDE_ROI, -1));
					}
					continue;
				}
				inpainted++;

				decisions.add(new BlockDecision(gridX, gridY, roiDistance, DECISION_INPAINTED, result.minChangedLayer));
				if (! params.dryRun) {
					backupWriter.writeBlock(params.fullDataset(), s0Attributes, tissueBlock);
					N5Utils.saveBlock(result.inpainted, tissueWriter, params.fullDataset(), s0Attributes, block.gridPosition);
				}
				LOG.info("inpaintPartition: inpainted block {} at min z-layer {} (roiDistance={})",
						 Arrays.toString(block.gridPosition), result.minChangedLayer, roiDistance);
			}
			LOG.info("inpaintPartition: partition summary: considered={}, nearRoi(<{}um)={}, present={}, inpainted={}",
					 considered, params.maxRoiDistance, nearRoi, present, inpainted);
		}

		return decisions.iterator();
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

	// Per-block decision categories recorded for diagnostics / visualization.
	static final int DECISION_OUTSIDE_ROI = 0;
	static final int DECISION_INSIDE_ROI = 1;
	static final int DECISION_INPAINTED = 2;

	static String decisionLabel(final int decision) {
		switch (decision) {
			case DECISION_OUTSIDE_ROI: return "outside_roi";
			case DECISION_INSIDE_ROI:  return "inside_roi";
			case DECISION_INPAINTED:   return "inpainted";
			default:                   return "unknown";
		}
	}

	/**
	 * The recorded decision for a single s0 block (its z grid position is always 0 — z is a single chunk). Near-ROI
	 * blocks with no tissue are intentionally NOT represented: the distance filter runs before any presence check, so
	 * block emptiness is only observable inside the ROI and is not treated as a category.
	 */
	static class BlockDecision implements Serializable {
		final long gridX;
		final long gridY;
		final double roiDistance;
		final int decision;
		final int minLayer; // minimal inpainted z-layer, or -1 when the block was not inpainted

		BlockDecision(final long gridX, final long gridY, final double roiDistance,
					  final int decision, final int minLayer) {
			this.gridX = gridX;
			this.gridY = gridY;
			this.roiDistance = roiDistance;
			this.decision = decision;
			this.minLayer = minLayer;
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
