package org.janelia.render.client.spark.multisem;


import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackIdNamingGroup;
import org.janelia.alignment.util.Grid;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.InpaintParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.spark.util.Downsample;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Spark client for filling holes in the tissue of exported wafer 60/61 N5 stack volumes.
 * <p>
 * Each selected render stack is inpainted independently, so stacks are the unit of distribution here: one Spark task
 * processes all blocks of one stack's export. A stack's export is located from its name (see {@link InpaintParameters}),
 * e.g. stack w61_s070_r00_gc_bc_par_cc_aso_3d_s3i in project w61_serial_070_to_079 uses
 * {@code /render/w61_serial_070_to_079/w61_s070_r00_gc_bc_par_cc_aso_3d_s3i___pixel} (tissue) and
 * {@code ..._s3i___mask/s0} (mask marking where image data is present ({@code mask == 255}) vs. missing).
 * <p>
 * The ROI point cloud is built in the tissue's s0 voxel frame from the render stack itself (so montage stitching is
 * accounted for): the first layer's tile bounds are fetched from the render web service, and each tile center (render
 * world pixels) is mapped to the voxel frame with {@code voxel = center - translate} (the neuroglancer group-level
 * {@code translate}, i.e. the stack bounding-box min). Each tile carries the {@code distance_roi} of its SFOV, read
 * from the xlog for the slab whose {@code id_serial} equals the serial parsed from the stack name: the tile's mfov
 * (0-based) maps to the xlog mfov axis as {@code render_mfov + 5} (the axis reserves 5 leading always-NaN rows), and
 * its sfov number (the 1-based {@code _s##} field) maps to the 0-based xlog sfov axis as {@code _s## - 1}.
 * <p>
 * For each full-resolution ({@code s0}) block, the cheap ROI-distance filter is applied first (interpolating
 * {@code distance_roi} at the block center via inverse-distance weighting; blocks whose interpolated distance is
 * {@code >= maxRoiDistance} are skipped without any I/O). The block is then read (skipping absent/empty blocks) and
 * every pixel where the mask is not 255 is filled with the average of the tissue in the sections above and below
 * (copying the single neighbor at the volume's z-boundary). Before a changed block is overwritten, the original block
 * is saved to the same grid position in the no paint group (e.g. {@code ..._s3i___pixel_no_paint/s0}). Finally, only
 * the pyramid blocks affected by the changed {@code s0} blocks are re-downsampled (again saving the originals first),
 * using the same per-block averaging as {@code N5DownsamplerSpark} so the pyramid stays consistent.
 * <p>
 * Reruns are safe: an original block is only saved the first time its block changes, and later runs inpaint from
 * that saved original, so a rerun produces the same result and never replaces a saved original with inpainted data.
 * A stack whose pixel group has the {@value #INPAINT_ATTRIBUTE} attribute (written when its inpainting completes)
 * is skipped.
 */
public class Wafer6061Inpainter
        implements Serializable, AlignmentPipelineStep {

	// Inverse-distance-weighting knobs for the ROI-distance interpolation; fixed for wafer 60/61.
	private static final int IDW_K = 8;
	private static final double IDW_POWER = 2.0;

	// Stacks are named w<wafer>_s<serial>_r<revision>_... (e.g. w61_s109_r00_gc_bc_par_cc_aso_3d_s3i); the serial is parsed from the start of the name.
	private static final Pattern SERIAL_IN_NAME = Pattern.compile("^w\\d+_s(\\d+)_");

	// xlog field layout (consumed by loadPointCloud). The xlog is a zarr: its arrays are stored C-order, but the
	// n5-zarr reader REVERSES the axis order, so the imglib2 axis indices below are the reverse of the C-order shape.
	//   field          C-order shape        imglib2 axes (what this code sees)   meaning
	//   id_serial       (slab,)              [slab]                              serial label per slab position
	//   distance_roi    (slab, mfov, sfov)   [sfov, mfov, slab]                  distance to ROI, um (scan-independent)
	// The slab axis index is HARDCODED (below) rather than discovered by matching sizes (sizes are not guaranteed to
	// be unique). After slicing the slab axis, distance_roi reduces to a 2-D (sfov, mfov) plane.
	private static final int DIST_AXIS_SLAB = 2;

	// The distance_roi mfov axis reserves 5 leading (always-NaN) positions before the first real mfov: real mfov data
	// starts at row 5 for every wafer-60/61 slab (verified constant + contiguous across all 399 finite slabs). Render
	// numbers each section's mfovs from 0, so the render mfov maps to the xlog mfov axis position as render_mfov + 5.
	private static final int MFOV_ROW_OFFSET = 5;

	/** Pixel group attribute written when a stack's inpainting completes (its presence makes reruns skip the stack). */
	public static final String INPAINT_ATTRIBUTE = "inpaint";

	private static final Logger LOG = LoggerFactory.getLogger(Wafer6061Inpainter.class);

	public static class Parameters extends CommandLineParameters {

		@ParametersDelegate
		public MultiProjectParameters multiProject = new MultiProjectParameters();

		@ParametersDelegate
		public InpaintParameters inpaint = new InpaintParameters();
	}

	/** Run the client with command line parameters. */
	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);

				LOG.info("runClient: entry, parameters={}", parameters);

				final Wafer6061Inpainter client = new Wafer6061Inpainter();
				client.createContextAndRun(parameters);
			}
		};
		clientRunner.run();
	}

	/** Empty constructor required for alignment pipeline steps. */
	public Wafer6061Inpainter() {
	}

	/** Create a spark context and run the client with the specified parameters. */
	public void createContextAndRun(final Parameters clientParameters) throws IOException {
		final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
		try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
			LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
			inpaintStacks(sparkContext, clientParameters.multiProject, clientParameters.inpaint);
		}
	}

	/** Validates the specified pipeline parameters are sufficient. */
	@Override
	public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
			throws IllegalArgumentException {

		final InpaintParameters inpaint = pipelineParameters.getInpaint();

		AlignmentPipelineParameters.validateRequiredElementExists("inpaint", inpaint);

		inpaint.validate();
	}

	/**
	 * Runs the {@link org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId#INPAINT_WAFER_60_61
	 * INPAINT_WAFER_60_61} step for the stacks in the pipeline's intensityCorrected stack group.
	 */
	@Override
	public void runPipelineStep(final JavaSparkContext sparkContext,
								final AlignmentPipelineParameters pipelineParameters)
			throws IllegalArgumentException, IOException {

		final StackIdNamingGroup namingGroup = pipelineParameters.getIntensityCorrectedNamingGroup();
		if (namingGroup == null) {
			throw new IllegalArgumentException("pipelineStackGroups.intensityCorrected must be defined");
		}

		final MultiProjectParameters multiProject = pipelineParameters.getMultiProject(namingGroup);
		inpaintStacks(sparkContext, multiProject, pipelineParameters.getInpaint());
	}

	/** Inpaints the export of each stack identified by the specified multiProject parameters, one stack per task. */
	public void inpaintStacks(final JavaSparkContext sparkContext,
							  final MultiProjectParameters multiProject,
							  final InpaintParameters inpaint)
			throws IllegalArgumentException, IOException {

		LOG.info("inpaintStacks: entry, inpaint={}", inpaint);

		inpaint.validate();

		final String baseDataUrl = multiProject.getBaseDataUrl();
		final List<StackId> stackIds = multiProject.stackIdWithZ.getStackIdList(multiProject.getDataClient());

		// fail fast for parameters that match no stacks
		if (stackIds.isEmpty()) {
			throw new IllegalArgumentException("no stacks match the multiProject parameters " + multiProject.toJson());
		}

		// Check every stack's export before inpainting anything, so that a missing or mismatched export fails the
		// run before any data is changed.
		final List<StackId> stacksToInpaint = new ArrayList<>();
		final List<StackId> completedStacks = new ArrayList<>();
		try (final N5Reader n5 = openN5Reader(inpaint.n5Path)) {
			for (final StackId stackId : stackIds) {
				inferSerial(stackId.getStack()); // throws if the stack name has no serial
				validateExport(n5, stackId, inpaint);
				if (readInpaintRecord(n5, inpaint.getPixelDataset(stackId)) != null) {
					completedStacks.add(stackId);
				} else {
					stacksToInpaint.add(stackId);
				}
			}
		}

		for (final StackId stackId : completedStacks) {
			LOG.info("inpaintStacks: skipping {} because {} has the '{}' attribute from an earlier run",
					 stackId.toDevString(), inpaint.getPixelDataset(stackId), INPAINT_ATTRIBUTE);
		}

		if (stacksToInpaint.isEmpty()) {
			LOG.info("inpaintStacks: exit, all {} stack(s) were already inpainted", stackIds.size());
			return;
		}

		if (! inpaint.dryRun) {
			createNoPaintDatasets(inpaint, stacksToInpaint);
		}

		LOG.info("inpaintStacks: distributing inpainting of {} stack(s) with parallelism {} (defaultParallelism={}), " +
				 "skipped {} already inpainted stack(s)",
				 stacksToInpaint.size(), stacksToInpaint.size(), sparkContext.defaultParallelism(),
				 completedStacks.size());

		final List<String> stackSummaries = sparkContext
				.parallelize(stacksToInpaint, stacksToInpaint.size())
				.map(stackId -> {
					LogUtilities.setupExecutorLog4j(stackId.toDevString());
					return inpaintStack(baseDataUrl, stackId, inpaint);
				})
				.collect();

		for (final String stackSummary : stackSummaries) {
			LOG.info("inpaintStacks: {}", stackSummary);
		}

		LOG.info("inpaintStacks: exit, {} {} stack(s)",
				 inpaint.dryRun ? "checked (dry run)" : "inpainted", stackSummaries.size());
	}

	/**
	 * Checks that the specified stack's export exists and can be inpainted block by block.
	 *
	 * @throws IllegalArgumentException
	 *   if the pixel or mask dataset is missing or their grids are not usable.
	 */
	static void validateExport(final N5Reader n5,
							   final StackId stackId,
							   final InpaintParameters inpaint)
			throws IllegalArgumentException {

		final String s0Dataset = inpaint.getPixelDataset(stackId) + "/s0";
		final String maskDataset = inpaint.getMaskS0Dataset(stackId);
		final String context = " (for stack " + stackId.toDevString() + " in " + inpaint.n5Path + ")";

		final DatasetAttributes s0Attributes = n5.getDatasetAttributes(s0Dataset);
		if (s0Attributes == null) {
			throw new IllegalArgumentException("tissue dataset " + s0Dataset + " does not exist" + context);
		}
		if (s0Attributes.getNumDimensions() != 3) {
			throw new IllegalArgumentException("expected a 3D tissue volume but " + s0Dataset + " has " +
											   s0Attributes.getNumDimensions() + " dimensions" + context);
		}

		final DatasetAttributes maskAttributes = n5.getDatasetAttributes(maskDataset);
		if (maskAttributes == null) {
			throw new IllegalArgumentException("mask dataset " + maskDataset + " does not exist" + context);
		}

		// Blocks are read and written one at a time (no whole-volume open, so no accumulating cell cache). That
		// requires z to be a single chunk, so that every z-1 / z+1 section the z-average needs lives inside the block;
		// and the mask is read by the tissue block's grid position, so the two datasets must share the same block grid
		// and origin.
		if (s0Attributes.getBlockSize()[2] < s0Attributes.getDimensions()[2]) {
			throw new IllegalArgumentException(
					"block-local inpainting requires z to be a single chunk, but " + s0Dataset +
					" has blockSize[2]=" + s0Attributes.getBlockSize()[2] +
					" < dimensions[2]=" + s0Attributes.getDimensions()[2] + context);
		}
		if (! Arrays.equals(maskAttributes.getBlockSize(), s0Attributes.getBlockSize()) ||
			! Arrays.equals(maskAttributes.getDimensions(), s0Attributes.getDimensions())) {
			throw new IllegalArgumentException(
					"mask grid " + Arrays.toString(maskAttributes.getDimensions()) + " @ " +
					Arrays.toString(maskAttributes.getBlockSize()) + " must match tissue grid " +
					Arrays.toString(s0Attributes.getDimensions()) + " @ " +
					Arrays.toString(s0Attributes.getBlockSize()) + context);
		}
	}

	/**
	 * Inpaints one stack's export (runs within a single Spark task).
	 *
	 * @return a one line summary of what was done.
	 */
	static String inpaintStack(final String baseDataUrl,
							   final StackId stackId,
							   final InpaintParameters inpaint)
			throws IOException {

		final String stack = stackId.getStack();
		final long serial = inferSerial(stack);
		final String pixelGroup = inpaint.getPixelDataset(stackId);
		final String noPaintGroup = inpaint.getNoPaintDataset(stackId);
		final String maskDataset = inpaint.getMaskS0Dataset(stackId);

		LOG.info("inpaintStack: entry, stack={}, serial={}, pixelGroup={}, maskDataset={}, noPaintGroup={}",
				 stackId.toDevString(), serial, pixelGroup, maskDataset, noPaintGroup);

		// Read the tissue level metadata and the world->voxel offset, and discover the pyramid levels.
		final double[] worldToVoxel;
		final List<String> levels = new ArrayList<>();
		final Map<String, DatasetAttributes> levelAttributes = new LinkedHashMap<>();
		int[] downsampleFactors = null;
		try (final N5Reader n5 = openN5Reader(inpaint.n5Path)) {

			validateExport(n5, stackId, inpaint);

			warnIfExportedFromDifferentStack(n5, pixelGroup, stack);

			// World->voxel offset for placing render tile centers: the neuroglancer 'translate' (the stack
			// bounding-box min in world pixels) is written on the multiscale GROUP, not on s0 (s0 only carries a
			// sub-pixel centering transform). May be null here; loadPointCloud falls back to the render stack bounds.
			worldToVoxel = readGroupTranslate(n5, pixelGroup);

			for (int scale = 0; ; scale++) {
				final String levelDataset = pixelGroup + "/s" + scale;
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
				downsampleFactors = readDownsamplingFactors(n5, pixelGroup, 3);
			}
		}
		LOG.info("inpaintStack: world->voxel translate={}, pyramid levels={}, downsampleFactors={}",
				 Arrays.toString(worldToVoxel), levels, Arrays.toString(downsampleFactors));

		// Load the (small) ROI point cloud: positions from the render stack, distances from the xlog.
		final RenderDataClient renderClient = new RenderDataClient(baseDataUrl, stackId.getOwner(), stackId.getProject());
		final PointCloud cloud = loadPointCloud(inpaint.xlogPath, serial, pixelGroup, renderClient, stack, worldToVoxel);
		LOG.info("inpaintStack: loaded {} ROI reference points for serial {} from {}",
				 cloud.size(), serial, stackId.toDevString());

		// The no paint datasets for every level were already created on the driver (see createNoPaintDatasets).

		final String s0Dataset = levels.getFirst();
		final List<long[]> modifiedS0 = inpaintS0Blocks(stack,
														serial,
														inpaint,
														s0Dataset,
														levelAttributes.get(s0Dataset),
														maskDataset,
														toNoPaintDataset(s0Dataset, pixelGroup, noPaintGroup),
														cloud);

		if ((! inpaint.dryRun) && (! modifiedS0.isEmpty()) && (levels.size() > 1)) {
			updatePyramid(inpaint.n5Path, pixelGroup, noPaintGroup, levels, levelAttributes, modifiedS0,
						  downsampleFactors);
		}

		if (! inpaint.dryRun) {
			try (final N5Writer n5 = openN5Writer(inpaint.n5Path)) {
				n5.setAttribute(pixelGroup,
								INPAINT_ATTRIBUTE,
								new InpaintRecord(inpaint, noPaintGroup, modifiedS0.size()));
			}
		}

		final String summary = stackId.toDevString() + ": " + modifiedS0.size() + " s0 block(s) " +
							   (inpaint.dryRun ? "would be inpainted (dry run)" : "inpainted");
		LOG.info("inpaintStack: exit, {}", summary);
		return summary;
	}

	// ------------------------------------------------------------------------------------------------
	// Step 1: load the per-slab ROI point cloud (positions from the render stack, distances from the xlog).
	// ------------------------------------------------------------------------------------------------

	/**
	 * Builds the per-slab ROI point cloud in the tissue s0 <b>voxel</b> frame. Positions come from the render stack
	 * (so montage stitching is accounted for): the first layer's tile bounds are fetched from the render web
	 * service, and each tile center (render world pixels) is mapped to the voxel frame with
	 * {@code voxel = center - worldToVoxel} ({@code worldToVoxel} is the neuroglancer group-level {@code translate},
	 * i.e. the stack bounding-box min; if it is null we fall back to the render stack bounds). Each tile carries the
	 * {@code distance_roi} of its SFOV, read from the xlog for the slab whose {@code id_serial} equals
	 * {@code serial}: the tile's mfov (0-based, parsed from the tileId) maps to the xlog mfov axis as
	 * {@code render_mfov + 5} (the axis reserves 5 leading always-NaN rows; see {@link #MFOV_ROW_OFFSET}), and its sfov
	 * number (the 1-based {@code _s##} field) maps to the 0-based xlog sfov axis as {@code _s## - 1}.
	 * Tiles whose SFOV has no (NaN) {@code distance_roi}, or an out-of-range mfov/sfov, are dropped.
	 */
	static PointCloud loadPointCloud(final String xlogPath,
									 final long serial,
									 final String pixelGroup,
									 final RenderDataClient renderClient,
									 final String stack,
									 final double[] worldToVoxel)
			throws IOException {

		// (a) xlog: the slab's 2-D distance_roi grid, materialized into a plain array so it outlives the xlog reader.
		final double[][] distBySfovMfov; // [rowMajorSfov0Based][mfov0Based]
		try (final N5Reader xlog = new N5Factory().openReader(xlogPath)) {

			final double[] idSerial = read1d(xlog);
			final int slabPosition = findSlabPosition(idSerial, serial);
			if (slabPosition < 0) {
				final long[] available = new long[idSerial.length];
				for (int i = 0; i < idSerial.length; i++) {
					available[i] = Math.round(idSerial[i]);
				}
				throw new IllegalArgumentException("serial " + serial + " not found in id_serial; available serials are " +
												   Arrays.toString(available));
			}

			final RandomAccessibleInterval<DoubleType> distAll = openDoubles(xlog, "distance_roi");
			requireSlabAxis(distAll, idSerial.length);

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
					 serial, slabPosition, nSfov, nMfov);
		}
		final int nSfov = distBySfovMfov.length;
		final int nMfov = nSfov > 0 ? distBySfovMfov[0].length : 0;

		// (b) render: the first layer's tile centers.
		final List<Double> zValues = renderClient.getStackZValues(stack);
		if (zValues.isEmpty()) {
			throw new IllegalArgumentException("render stack " + stack + " has no z layers");
		}
		final double firstZ = zValues.getFirst();
		final List<TileBounds> tiles = renderClient.getTileBounds(stack, firstZ);

		final double offsetX;
		final double offsetY;
		if (worldToVoxel != null) {
			offsetX = worldToVoxel[0];
			offsetY = worldToVoxel[1];
		} else {
			final Bounds stackBounds = renderClient.getStackMetaData(stack).getStackBounds();
			if (stackBounds == null || stackBounds.getMinX() == null || stackBounds.getMinY() == null) {
				throw new IllegalArgumentException("N5 group " + pixelGroup + " has no neuroglancer 'translate' and " +
												   "render stack " + stack + " has no bounds; cannot map world coordinates to voxels");
			}
			offsetX = stackBounds.getMinX();
			offsetY = stackBounds.getMinY();
			LOG.warn("loadPointCloud: N5 group {} has no 'translate'; using render stack bounds min ({}, {}) as the world->voxel offset",
					 pixelGroup, offsetX, offsetY);
		}

		// Attach each tile's distance_roi (by mfov + sfov) and place its center in the voxel frame.
		final List<Double> xs = new ArrayList<>();
		final List<Double> ys = new ArrayList<>();
		final List<Double> ds = new ArrayList<>();
		int noDistance = 0;
		int outOfRange = 0;
		for (final TileBounds tile : tiles) {
			final String tileId = tile.getTileId();
			// render mfov is 0-based per section; the xlog mfov axis reserves 5 leading NaN rows (see MFOV_ROW_OFFSET).
			final int mfov = Integer.parseInt(MultiSemUtilities.getSimpleMfovForTileId(tileId).substring(1)) + MFOV_ROW_OFFSET; // m0013 -> 13 -> row 18
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
	private static void requireSlabAxis(final RandomAccessibleInterval<?> img, final long slabCount) {
		final int distAxis = Wafer6061Inpainter.DIST_AXIS_SLAB;
		if (img.numDimensions() <= distAxis || img.dimension(distAxis) != slabCount) {
			throw new IllegalArgumentException("xlog field '" + "distance_roi" + "' has dimensions " +
											   Arrays.toString(img.dimensionsAsLongArray()) + " but the hardcoded layout " +
											   "expects " + slabCount + " slabs on axis " + Wafer6061Inpainter.DIST_AXIS_SLAB +
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
	private static double[] read1d(final N5Reader n5) {
		final RandomAccessibleInterval<DoubleType> img = openDoubles(n5, "id_serial");
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
	// Steps 2 + 3: filter and inpaint the s0 blocks of one stack.
	// ------------------------------------------------------------------------------------------------

	/**
	 * Filters and inpaints every s0 block of one stack's export, saving the original version of each changed block
	 * to the no paint dataset the first time it changes.
	 *
	 * @return grid positions of the s0 blocks that were (or in a dry run would be) inpainted.
	 */
	private static List<long[]> inpaintS0Blocks(final String stack,
												final long serial,
												final InpaintParameters inpaint,
												final String s0Dataset,
												final DatasetAttributes s0Attributes,
												final String maskDataset,
												final String noPaintS0Dataset,
												final PointCloud cloud) {

		final List<Grid.Block> s0Blocks = Grid.create(s0Attributes.getDimensions(), s0Attributes.getBlockSize());

		// A single line carries the grid extent and z-depth the visualization needs; the per-block
		// 'blockDecision ...' lines follow (see logDecision).
		final long[] dims = s0Attributes.getDimensions();
		final int[] blockSize = s0Attributes.getBlockSize();
		LOG.info("inpaintS0Blocks: diagnostics metadata stack={} serial={} maxRoiDistance={} gridX={} gridY={} zLayers={}",
				 stack, serial, inpaint.maxRoiDistance,
				 (dims[0] + blockSize[0] - 1) / blockSize[0], (dims[1] + blockSize[1] - 1) / blockSize[1], dims[2]);

		final List<long[]> modified = new ArrayList<>();
		final KNearestNeighborSearchOnKDTree<DoubleType> search = cloud.buildSearch();

		try (final N5Reader reader = openN5Reader(inpaint.n5Path);
			 final N5Writer writer = inpaint.dryRun ? null : openN5Writer(inpaint.n5Path)) {

			final DatasetAttributes maskAttributes = reader.getDatasetAttributes(maskDataset);

			// The no paint dataset only exists after a (possibly interrupted) earlier run or once it is created
			// before a real run, so a first dry run never looks for saved originals.
			final boolean hasNoPaintDataset = reader.datasetExists(noPaintS0Dataset);

			long nearRoi = 0;
			long present = 0;
			long previouslyInpainted = 0;
			for (final Grid.Block block : s0Blocks) {
				final long gridX = block.gridPosition[0];
				final long gridY = block.gridPosition[1];

				// (1) ROI-distance filter, computed with no I/O. The cloud is already in the s0 voxel frame, so the
				// block center (voxel index) queries it directly; distance_roi is interpolated by inverse-distance
				// weighting (the interpolated value is in microns regardless of the voxel-space query units).
				final double centerX = block.offset[0] + block.dimensions[0] / 2.0;
				final double centerY = block.offset[1] + block.dimensions[1] / 2.0;
				final double roiDistance = cloud.interpolate(search, centerX, centerY);
				if (! (roiDistance < inpaint.maxRoiDistance)) {
					logDecision(stack, gridX, gridY, "outside_roi", -1, roiDistance);
					continue;
				}
				nearRoi++;

				// (2) presence check: readBlock returns null for absent (empty) blocks. Because z is a single chunk
				// (checked by validateExport), every z-1 / z+1 section the z-average reads lives inside this same
				// block. Near-ROI empty blocks are not logged: the distance filter runs before this presence check,
				// so block emptiness is not observable outside the ROI.
				final DataBlock<?> currentBlock = reader.readBlock(s0Dataset, s0Attributes, block.gridPosition);
				if (currentBlock == null) {
					continue;
				}
				present++;

				// A block that an earlier (possibly interrupted) run changed has its original saved in the no paint
				// dataset. Inpainting from that original reproduces the earlier result instead of inpainting
				// already inpainted data, and the saved original is never replaced.
				final DataBlock<?> savedOriginal = hasNoPaintDataset ?
												   reader.readBlock(noPaintS0Dataset, s0Attributes, block.gridPosition) :
												   null;
				if (savedOriginal != null) {
					previouslyInpainted++;
				}
				final DataBlock<?> originalBlock = savedOriginal == null ? currentBlock : savedOriginal;

				// (3) inpaint: fill mask!=255 pixels with the z-average of the sections above/below. The mask is read
				// by the same grid position (the datasets share a grid, checked by validateExport); an absent mask
				// block counts as all-background (all holes), matching a zero-filled whole-volume read of a missing
				// chunk.
				final DataBlock<?> maskBlock = reader.readBlock(maskDataset, maskAttributes, block.gridPosition);
				final InpaintResult result = inpaintBlock(asByteImg(originalBlock, block.dimensions),
														  asByteImg(maskBlock, block.dimensions),
														  block.dimensions);
				if (! result.changed) {
					logDecision(stack, gridX, gridY, "inside_roi", -1, roiDistance);
					continue;
				}

				modified.add(block.gridPosition);
				if (writer != null) {
					if (savedOriginal == null) {
						writer.writeBlock(noPaintS0Dataset, s0Attributes, currentBlock);
					}
					N5Utils.saveBlock(result.inpainted, writer, s0Dataset, s0Attributes, block.gridPosition);
				}
				logDecision(stack, gridX, gridY, "inpainted", result.minChangedLayer, roiDistance);
			}
			LOG.info("inpaintS0Blocks: summary for {}: considered={}, nearRoi(<{}um)={}, present={}, inpainted={} (previously inpainted={})",
					 stack, s0Blocks.size(), inpaint.maxRoiDistance, nearRoi, present, modified.size(),
					 previouslyInpainted);
		}

		return modified;
	}

	/**
	 * Logs one greppable per-block decision line consumed by the visualization (plot_inpainter_diagnostics.py). Keeping
	 * a fixed {@code blockDecision key=value ...} shape means the log is the single source of truth — no file is written
	 * and no decision logic is re-derived downstream.
	 */
	private static void logDecision(final String stack, final long gridX, final long gridY, final String decision,
									final int minLayer, final double roiDistance) {
		LOG.info("blockDecision stack={} gridX={} gridY={} decision={} minLayer={} roiDistance={}",
				 stack, gridX, gridY, decision, minLayer, roiDistance);
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
	 * Produces the inpainted version of a single block: every pixel where the mask is not 255 is replaced with the
	 * average of the tissue in the sections above and below (the single neighbor is copied at the block's z-boundary).
	 * Both {@code tissueBlock} and {@code maskBlock} are block-local images with dimensions {@code blockDimensions};
	 * because the volume is a single z-chunk, the block's z-boundary is the volume's z-boundary, so no neighbouring
	 * block is needed for the z-average.
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
			if (maskAccess.setPositionAndGet(local).get() == 255) {
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
	// Step 4: selectively re-downsample only the pyramid blocks affected by the modified s0 blocks.
	// ------------------------------------------------------------------------------------------------

	private static void updatePyramid(final String n5Path,
									  final String pixelGroup,
									  final String noPaintGroup,
									  final List<String> levels,
									  final Map<String, DatasetAttributes> levelAttributes,
									  final List<long[]> modifiedS0,
									  final int[] factors) {

		try (final N5Writer n5 = openN5Writer(n5Path)) {

			List<long[]> modifiedPrevious = modifiedS0;

			for (int scale = 1; scale < levels.size(); scale++) {
				final String fromDataset = levels.get(scale - 1);
				final String toDataset = levels.get(scale);
				final String noPaintToDataset = toNoPaintDataset(toDataset, pixelGroup, noPaintGroup);
				final DatasetAttributes toAttributes = levelAttributes.get(toDataset);
				final CellGrid cellGrid = new CellGrid(toAttributes.getDimensions(), toAttributes.getBlockSize());

				// affected blocks: previous-level grid position p maps to this-level block p / factor.
				final Map<String, long[]> affected = new LinkedHashMap<>();
				for (final long[] p : modifiedPrevious) {
					final long[] g = affectedBlock(p, factors);
					affected.putIfAbsent(Arrays.toString(g), g);
				}
				final List<long[]> affectedBlocks = new ArrayList<>(affected.values());
				LOG.info("updatePyramid: re-downsampling {} block(s) for {}", affectedBlocks.size(), toDataset);

				for (final long[] gridPosition : affectedBlocks) {
					downsampleBlock(n5, cellGrid, gridPosition, fromDataset, toDataset, toAttributes,
									noPaintToDataset, factors);
				}

				modifiedPrevious = affectedBlocks;
			}
		}
	}

	/**
	 * Rebuilds a single pyramid block from the (already updated) previous level with the same per-block math as
	 * {@code N5DownsamplerSpark}, so the result matches the rest of the pyramid. The original block is saved to the
	 * no paint dataset before it is overwritten unless an earlier run already saved it.
	 */
	private static void downsampleBlock(final N5Writer n5,
										final CellGrid cellGrid,
										final long[] gridPosition,
										final String fromDataset,
										final String toDataset,
										final DatasetAttributes toAttributes,
										final String noPaintToDataset,
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

		// The source level is opened for each block (a lazy open, so only the source chunks this block needs are
		// read). Because one task updates every affected block of a stack, a single open shared by all blocks would
		// let its cell cache hold on to every chunk read so far.
		final RandomAccessibleInterval<UnsignedByteType> source = N5Utils.open(n5, fromDataset);
		final RandomAccessibleInterval<UnsignedByteType> sourceBlock = Views.offsetInterval(source, sourceMin, sourceSize);
		final Img<UnsignedByteType> targetBlock = ArrayImgs.unsignedBytes(targetSize);
		Downsample.downsample(sourceBlock, targetBlock, factors);

		// save the original block (if present) before overwriting, unless an earlier run already saved it.
		final DataBlock<?> savedOriginal = n5.readBlock(noPaintToDataset, toAttributes, gridPosition);
		if (savedOriginal == null) {
			final DataBlock<?> originalBlock = n5.readBlock(toDataset, toAttributes, gridPosition);
			if (originalBlock != null) {
				n5.writeBlock(noPaintToDataset, toAttributes, originalBlock);
			}
		}

		// delete first so a block that became empty does not leave a stale remnant.
		N5Utils.deleteBlock(targetBlock, n5, toDataset, gridPosition);
		N5Utils.saveNonEmptyBlock(targetBlock, n5, toDataset, gridPosition, new UnsignedByteType());
		LOG.info("downsampleBlock: updated {} block {}", toDataset, Arrays.toString(gridPosition));
	}

	// ------------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------------

	/** Parses the serial label from a stack name like {@code w61_s109_r00_gc_bc_par_cc_aso_3d_s3i} (109). */
	static long inferSerial(final String stack) {
		final Matcher matcher = SERIAL_IN_NAME.matcher(stack);
		if (matcher.find()) {
			return Long.parseLong(matcher.group(1));
		}
		throw new IllegalArgumentException("could not infer the serial from stack name '" + stack +
										   "' because it does not start with w<wafer>_s<serial>_");
	}

	/**
	 * Creates the no paint dataset for every pyramid level of every specified stack, one dataset at a time on the
	 * driver, so that the executors only write blocks into existing datasets.
	 * <p>
	 * On GCS, {@code createDataset} writes a directory marker object for every component of the dataset's path, so
	 * shared ancestors like the export root are rewritten for each dataset. When every Spark task created its own
	 * datasets at the same time, those rewrites exceeded GCS's per-object mutation rate limit (about one per second)
	 * and the 429 responses were not retried by the GCS client. Creating them serially and retrying rate limited
	 * creates with backoff avoids that.
	 */
	private static void createNoPaintDatasets(final InpaintParameters inpaint,
											  final List<StackId> stackIds)
			throws IOException {

		LOG.info("createNoPaintDatasets: entry, creating no paint datasets for {} stack(s)", stackIds.size());

		int createdCount = 0;
		int existingCount = 0;
		try (final N5Writer n5 = openN5Writer(inpaint.n5Path)) {
			for (final StackId stackId : stackIds) {
				final String pixelGroup = inpaint.getPixelDataset(stackId);
				final String noPaintGroup = inpaint.getNoPaintDataset(stackId);
				for (int scale = 0; ; scale++) {
					final String levelDataset = pixelGroup + "/s" + scale;
					if (! n5.datasetExists(levelDataset)) {
						break;
					}
					final String noPaintDataset = toNoPaintDataset(levelDataset, pixelGroup, noPaintGroup);
					if (n5.datasetExists(noPaintDataset)) {
						existingCount++;
					} else {
						final DatasetAttributes attributes = n5.getDatasetAttributes(levelDataset);
						createWithRateLimitRetries(() -> n5.createDataset(noPaintDataset, attributes), noPaintDataset);
						createdCount++;
					}
				}
			}
		}

		LOG.info("createNoPaintDatasets: exit, created {} and found {} existing no paint dataset(s)",
				 createdCount, existingCount);
	}

	/**
	 * Runs the specified create, retrying with exponential backoff (2s, 4s, ... up to 64s, about 4 minutes in
	 * total) while it fails because of a GCS rate limit. Any other failure is thrown immediately.
	 */
	private static void createWithRateLimitRetries(final Runnable create,
												   final String path)
			throws IOException {
		final int maxRetries = 7;
		long delayMs = 2000;
		for (int retry = 0; ; retry++) {
			try {
				create.run();
				return;
			} catch (final RuntimeException e) {
				if ((retry >= maxRetries) || (! isRateLimitError(e))) {
					throw e;
				}
				LOG.warn("createWithRateLimitRetries: rate limited creating {}, retry {} of {} in {}ms",
						 path, retry + 1, maxRetries, delayMs);
				try {
					Thread.sleep(delayMs);
				} catch (final InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted while waiting to retry create of " + path, ie);
				}
				delayMs *= 2;
			}
		}
	}

	/** Returns true if the specified exception or any of its causes is a GCS 429 rate limit error. */
	private static boolean isRateLimitError(final Throwable throwable) {
		for (Throwable t = throwable; t != null; t = t.getCause()) {
			final String message = t.getMessage();
			if ((message != null) &&
				(message.contains("429") || message.contains("rateLimitExceeded") || message.contains("rate limit"))) {
				return true;
			}
		}
		return false;
	}

	/** Maps a pixel level dataset (e.g. {@code <pixelGroup>/s2}) to its no paint dataset ({@code <noPaintGroup>/s2}). */
	private static String toNoPaintDataset(final String levelDataset,
										   final String pixelGroup,
										   final String noPaintGroup) {
		return noPaintGroup + levelDataset.substring(pixelGroup.length());
	}

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
	 * or returns null if it is absent. The render N5 export writes it on the group, not on {@code s0} (s0 only carries
	 * a sub-pixel centering {@code transform}); callers fall back to the render stack bounds.
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

	/**
	 * Logs a warning if the group's {@code renderExport} metadata (written by render's N5 export) names a different
	 * stack than the one being inpainted, since the tile positions are then read from a different stack than the
	 * one that was exported.
	 */
	private static void warnIfExportedFromDifferentStack(final N5Reader n5,
														 final String pixelGroup,
														 final String stack) {
		try {
			final RenderExport export = n5.getAttribute(pixelGroup, "renderExport", RenderExport.class);
			final String exportedStack = (export == null || export.runParameters == null) ?
										 null : export.runParameters.stack;
			if (exportedStack == null) {
				LOG.warn("warnIfExportedFromDifferentStack: {} has no renderExport stack, assuming it was exported from {}",
						 pixelGroup, stack);
			} else if (! exportedStack.equals(stack)) {
				LOG.warn("warnIfExportedFromDifferentStack: {} was exported from stack {} but tile positions are read from stack {}",
						 pixelGroup, exportedStack, stack);
			}
		} catch (final Exception e) {
			LOG.warn("warnIfExportedFromDifferentStack: could not read renderExport from {} ({})", pixelGroup, e.getMessage());
		}
	}

	/** Reads the completion record a finished run wrote to the pixel group, or returns null if there is none. */
	private static InpaintRecord readInpaintRecord(final N5Reader n5, final String pixelGroup) {
		return n5.getAttribute(pixelGroup, INPAINT_ATTRIBUTE, InpaintRecord.class);
	}

	/** Opens an N5 reader for a tissue container (explicit N5 format; local path or gs://). */
	private static N5Reader openN5Reader(final String path) {
		return new N5Factory().openReader(StorageFormat.N5, path);
	}

	/** Opens an N5 writer for a tissue container (explicit N5 format; local path or gs://). */
	private static N5Writer openN5Writer(final String path) {
		return new N5Factory().openWriter(StorageFormat.N5, path);
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
	 * Minimal GSON view of the group's {@code renderExport} attribute (only the stack name is needed;
	 * all other fields written by the export are ignored).
	 */
	private static class RenderExport {
		RunParameters runParameters;

		private static class RunParameters {
			String stack;
		}
	}

	/** Completion record written to a stack's pixel group (as the {@value #INPAINT_ATTRIBUTE} attribute). */
	private static class InpaintRecord {
		String completedAt;
		double maxRoiDistance;
		String xlogPath;
		String noPaintDataset;
		int inpaintedS0BlockCount;

		@SuppressWarnings("unused")
		InpaintRecord() {
		}

		InpaintRecord(final InpaintParameters inpaint,
					  final String noPaintDataset,
					  final int inpaintedS0BlockCount) {
			this.completedAt = Instant.now().toString();
			this.maxRoiDistance = inpaint.maxRoiDistance;
			this.xlogPath = inpaint.xlogPath;
			this.noPaintDataset = noPaintDataset;
			this.inpaintedS0BlockCount = inpaintedS0BlockCount;
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

		KNearestNeighborSearchOnKDTree<DoubleType> buildSearch() {
			final List<RealPoint> points = new ArrayList<>(xs.length);
			final List<DoubleType> values = new ArrayList<>(xs.length);
			for (int i = 0; i < xs.length; i++) {
				points.add(new RealPoint(xs[i], ys[i]));
				values.add(new DoubleType(dists[i]));
			}
			final KDTree<DoubleType> tree = new KDTree<>(values, points);
			return new KNearestNeighborSearchOnKDTree<>(tree, Math.min(Wafer6061Inpainter.IDW_K, xs.length));
		}

		/** Inverse-distance-weighted interpolation of the distance value at (x, y). */
		double interpolate(final KNearestNeighborSearchOnKDTree<DoubleType> search,
						   final double x,
						   final double y) {
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
				final double w = 1.0 / Math.pow(r, Wafer6061Inpainter.IDW_POWER);
				numerator += w * v;
				denominator += w;
			}
			return numerator / denominator;
		}
	}
}
