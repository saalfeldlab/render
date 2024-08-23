package org.janelia.render.client.multisem;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.trakem2.transform.AffineModel2D;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccess;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.Grid;
import org.janelia.render.client.RenderDataClient;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class ResaveSegmentations {

	private static final Logger LOG = LoggerFactory.getLogger(ResaveSegmentations.class);

	private final String baseDataUrl;
	private final String owner;
	private final String stackNumber;
	private final String sourceStackSuffix;
	private final String targetStackSuffix;
	private final String sourceN5;
	private final String targetN5;
	private final String dataset;
	private final String layerOriginCsv;
	private final int[] blockSize;
	private final int numThreads;
	private Interval scanTransformedTemplateTile;

	public ResaveSegmentations() {
		baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
		owner = "hess_wafer_53_center7";
		stackNumber = "s001_m239";
		sourceStackSuffix = "_hayworth_alignment_replica";
		targetStackSuffix = "_align_no35";
		sourceN5 = "/nrs/hess/data/hess_wafer_53/mapback_michal/base240715/test";
		targetN5 = "/home/innerbergerm@hhmi.org/big-data/kens-alignment/segmentations.n5";
		dataset = "/n5";
		blockSize = new int[] {512, 512, 64};
		// Create with LayerOrigin.main()
		numThreads = 2;
		layerOriginCsv = "layerOrigins.csv";
	}

	public static void main(final String[] args) throws IOException {
		new ResaveSegmentations().run();
	}

	public void run() throws IOException {
		// Get mapping "layer in exported stack" -> "stack name + layer" for the stack under consideration
		final Map<Integer, LayerOrigin> layerOrigins = LayerOrigin.getRangeForStack(layerOriginCsv, stackNumber);
		final LayerOrigin firstLayerOrigin = layerOrigins.values().stream().findFirst().orElseThrow();
		final String stackNumber = firstLayerOrigin.stack();
		final RenderDataClient dataClient = new RenderDataClient(baseDataUrl, owner, firstLayerOrigin.project());

		// Get the stack bounds for the target stack to create the target dataset
		final String targetStack = stackNumber + targetStackSuffix;
		final StackMetaData targetStackMetaData = dataClient.getStackMetaData(targetStack);
		final Bounds targetBounds = targetStackMetaData.getStackBounds();

		// Get other attributes for the target dataset from the source dataset
		LOG.info("Resaving segmentations from {}/{} to {}/{}", sourceN5, dataset, targetN5, stackNumber);
		final N5Reader sourceReader = new N5FSReader(sourceN5);
		final DatasetAttributes attributes = sourceReader.getDatasetAttributes(dataset);
		final RandomAccessibleInterval<UnsignedLongType> segmentations = N5Utils.open(sourceReader, dataset);

		// Create dataset in output N5 container
		final DatasetAttributes targetAttributes = new DatasetAttributes(
				new long[] {targetBounds.getWidth(), targetBounds.getHeight(), (long) targetStackMetaData.getStackBounds().getDeltaZ()},
				blockSize,
				attributes.getDataType(),
				new GzipCompression());
		try (final N5Writer targetWriter = new N5FSWriter(targetN5)) {
			targetWriter.createDataset(stackNumber, targetAttributes);
		}

		// Create a grid over the relevant part of the target stack
		final ResolvedTileSpecCollection sourceTiles = dataClient.getResolvedTiles(stackNumber + sourceStackSuffix, null);
		final ResolvedTileSpecCollection targetTiles = dataClient.getResolvedTiles(targetStack, null);
		final List<long[][]> grid = createGridOverRelevantTiles(targetAttributes, targetTiles, sourceTiles);
		scanTransformedTemplateTile = createRawBoundingBox(targetTiles.getTileSpecs().stream().findAny().orElseThrow());

		// Fuse data block by block
		final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
		final List<Future<?>> tasks = new ArrayList<>();
		for (final long[][] gridBlock : grid) {
			final Future<?> task = executor.submit(() -> fuseBlock(sourceTiles.getTileIdToSpecMap(), targetTiles.getTileIdToSpecMap(), segmentations, gridBlock, layerOrigins, targetN5, stackNumber));
			tasks.add(task);
		}

		try {
			int count = 0;
			for (final Future<?> task : tasks) {
				task.get();
				LOG.info("Processed {} of {} blocks", ++count, grid.size());
			}
			executor.shutdown();
		} catch (final Exception e) {
			throw new RuntimeException(e);
		} finally {
			sourceReader.close();
		}
	}

	private static List<long[][]> createGridOverRelevantTiles(
			final DatasetAttributes targetAttributes,
			final ResolvedTileSpecCollection targetTiles,
			final ResolvedTileSpecCollection sourceTiles
	) {
		// Start with a grid over the full dimensions, then only keep blocks that intersect with tiles that will be used in writing
		final List<long[][]> fullGrid = Grid.create(targetAttributes.getDimensions(), targetAttributes.getBlockSize());

		// Only keep tiles that are in the source stack, since there are no transformations for the others and hence there can be no data
		targetTiles.retainTileSpecs(sourceTiles.getTileIds());
		targetTiles.recalculateBoundingBoxes();
		final Rectangle remainingStackBounds = targetTiles.toBounds().toRectangle();
		final List<long[][]> relevantGridBlocks = fullGrid.stream()
				.filter(gridBlock -> {
					final long[] blockOffset = gridBlock[0];
					final long[] blockSize = gridBlock[1];
					return remainingStackBounds.intersects(blockOffset[0], blockOffset[1], blockSize[0], blockSize[1]);
				}).collect(Collectors.toList());

		LOG.info("Resaving {} (relevant) of {} (total) blocks", relevantGridBlocks.size(), fullGrid.size());
		return relevantGridBlocks;
	}

	private void fuseBlock(
			final Map<String, TileSpec> sourceTiles,
			final Map<String, TileSpec> targetTiles,
			final RandomAccessibleInterval<UnsignedLongType> segmentations,
			final long[][] gridBlock,
			final Map<Integer, LayerOrigin> layerOrigins,
			final String n5path,
			final String dataset
	) {
		final long[] blockSize = gridBlock[1];
		final long[] blockOffset = gridBlock[0];
		final Interval block = Intervals.translate(new FinalInterval(blockSize), blockOffset);
		final Img<UnsignedLongType> blockData = ArrayImgs.unsignedLongs(blockSize);

		final Map<Integer, Integer> zRenderToExport = new HashMap<>();
		layerOrigins.forEach((exportZ, layerOrigin) -> {
			final int zRender = getStackZValue(layerOrigin);
			if (zRender >= 0) {
				// The export starts with a blank layer, hence the +1
				zRenderToExport.put(zRender, exportZ + 1);
			}
		});

		final Map<Integer, List<AffineModel2D>> fromTargetTransforms = new HashMap<>();
		final Map<Integer, List<AffineModel2D>> toSourceTransforms = new HashMap<>();

		for (final TileSpec sourceTileSpec : sourceTiles.values()) {
			final TileSpec targetTileSpec = targetTiles.get(sourceTileSpec.getTileId());
			final int zInRender = targetTileSpec.getZ().intValue();

			if (! intersect(targetTileSpec, block)) {
				final List<AffineModel2D> layerFromTargetTransforms = fromTargetTransforms.computeIfAbsent(zInRender, k -> new ArrayList<>());
				final List<AffineModel2D> layerToSourceTransforms = toSourceTransforms.computeIfAbsent(zInRender, k -> new ArrayList<>());
				layerFromTargetTransforms.add(concatenateTransforms(targetTileSpec.getTransformList()).createInverse());
				layerToSourceTransforms.add(concatenateTransforms(sourceTileSpec.getTransformList()));
			}
		}

		if (fromTargetTransforms.isEmpty()) {
			// No tiles in this block
			return;
		}


		// Position the segmentation and block data
		final long[] cropOffset = new long[]{6250, 6250, 0};
		final RandomAccessibleInterval<UnsignedLongType> positionedSegmentation = Views.translate(Views.dropSingletonDimensions(segmentations), cropOffset);
		final RandomAccessibleInterval<UnsignedLongType> positionedBlock = Views.translate(blockData, blockOffset);

		final Cursor<UnsignedLongType> targetCursor = Views.iterable(positionedBlock).localizingCursor();
		final RealRandomAccess<UnsignedLongType> sourceRa = Views.interpolate(Views.extendZero(positionedSegmentation), new NearestNeighborInterpolatorFactory<>()).realRandomAccess();
		final double[] currentPoint = new double[targetCursor.numDimensions()];

		// Fill the target block pixel by pixel
		while (targetCursor.hasNext()) {
			final UnsignedLongType pixel = targetCursor.next();
			targetCursor.localize(currentPoint);
			final int zInRender = (int) currentPoint[2] + 1;
			final Integer zInExport = zRenderToExport.get(zInRender);
			final List<AffineModel2D> layerFromTargetTransforms = fromTargetTransforms.get(zInRender);
			final List<AffineModel2D> layerToSourceTransforms = toSourceTransforms.get(zInRender);

			if (zInExport == null | layerFromTargetTransforms == null) {
				continue;
			}
			currentPoint[2] = zInExport;

			for (int i = 0; i < layerFromTargetTransforms.size(); ++i) {
				layerFromTargetTransforms.get(i).applyInPlace(currentPoint);

				if (Intervals.contains(scanTransformedTemplateTile, new RealPoint(currentPoint))) {
					layerToSourceTransforms.get(i).applyInPlace(currentPoint);
					sourceRa.setPosition(currentPoint);
					final UnsignedLongType sourcePixel = sourceRa.get();
					if (sourcePixel.get() != 0) {
						pixel.set(sourcePixel);
						// Take the first hit
						break;
					}
				}
				targetCursor.localize(currentPoint);
			}
		}

		// Write block if it's not empty
		try (final N5Writer writer = new N5FSWriter(n5path)) {
			final long[] gridOffset = gridBlock[2];
			N5Utils.saveNonEmptyBlock(blockData, writer, dataset, gridOffset, new UnsignedLongType(0));
		}
	}

	private int getStackZValue(final LayerOrigin layerOrigin) {
		if (layerOrigin.stack().equals("MISSING")) {
			// Skip the one missing layer
			return -1;
		}
		final int sectionId = layerOrigin.zLayer();
		// In the stacks, layer 35 was omitted
		return (sectionId > 34) ? sectionId - 1 : sectionId;
	}

	private static boolean intersect(final TileSpec tileSpec, final Interval interval) {
		final TileBounds tileBounds = tileSpec.toTileBounds();
		final Interval tileBoundingBox = new FinalInterval(new long[]{tileBounds.getMinX().longValue(), tileBounds.getMinY().longValue()},
														   new long[]{tileBounds.getMaxX().longValue() - 1, tileBounds.getMaxY().longValue() - 1});
		return Intervals.isEmpty(Intervals.intersect(tileBoundingBox, interval));
	}

	// This uses that the first transform is the scanning correction, which is the same for all tiles
	// Since this is not invertible, we forward-transform the original tile bounds and skip this transform when applying
	// the inverse transforms
	// I.e., instead of going source -> tile -> target, we go source -> scan corrected tile -> target
	private Interval createRawBoundingBox(final TileSpec tileSpec) {
		final List<CoordinateTransform> transforms = tileSpec.getTransformList().getList(null);
		final CoordinateTransform scanTransform = transforms.get(0);
		if (! scanTransform.getClass().getName().equals("org.janelia.alignment.transform.ExponentialFunctionOffsetTransform")) {
			throw new IllegalArgumentException("First transform is not the scan correction");
		}

		final TileSpec tsWithOnlyScanCorrection = tileSpec.slowClone();
		for (int i = 1; i < transforms.size(); ++i) {
			tsWithOnlyScanCorrection.removeLastTransformSpec();
		}

		tsWithOnlyScanCorrection.deriveBoundingBox(tsWithOnlyScanCorrection.getMeshCellSize(), true);
		final TileBounds bounds = tsWithOnlyScanCorrection.toTileBounds();
		return new FinalInterval(new long[]{bounds.getMinX().longValue(), bounds.getMinY().longValue()},
								 new long[]{bounds.getMaxX().longValue() - 1, bounds.getMaxY().longValue() - 1});
	}

	/**
	 * Takes two a list of transforms and concatenates them into a single affine transformation.
	 *
	 * @param transforms transform list
	 * @return concatenated affine transformation
	 */
	private static AffineModel2D concatenateTransforms(final CoordinateTransformList<CoordinateTransform> transforms) {
		final CoordinateTransform firstTransform = transforms.get(0);
		if (firstTransform.getClass().getName().equals("org.janelia.alignment.transform.ExponentialFunctionOffsetTransform")) {
			// The scan correction is not an affine transformation and taken care of by transforming the original tile bounds elsewhere
			transforms.remove(0);
		}

		final AffineModel2D fullModel = new AffineModel2D();
		final AffineModel2D singleModel = new AffineModel2D();

		for (final CoordinateTransform currentTransform : transforms.getList(null)) {
			final AbstractAffineModel2D<?> currentModel = ensureAbstractAffineModel2D(currentTransform);
			singleModel.set(currentModel.createAffine());
			fullModel.preConcatenate(singleModel);
		}

		return fullModel;
	}

	private static AbstractAffineModel2D<?> ensureAbstractAffineModel2D(final CoordinateTransform transform) {
		if (! (transform instanceof AbstractAffineModel2D)) {
			throw new IllegalArgumentException("transform is not an affine model");
		}
		return (AbstractAffineModel2D<?>) transform;
	}
}
