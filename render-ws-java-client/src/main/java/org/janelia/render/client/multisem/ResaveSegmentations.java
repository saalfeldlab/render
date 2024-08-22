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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
		layerOriginCsv = "layerOrigins.csv";
	}

	public static void main(final String[] args) throws IOException {
		new ResaveSegmentations().run();
	}

	public void run() throws IOException {
		// Create dataset in output N5 container
		final N5Reader sourceReader = new N5FSReader(sourceN5);

		final Map<Integer, LayerOrigin> layerOrigins = LayerOrigin.getRangeForStack(layerOriginCsv, stackNumber);
		final LayerOrigin firstLayerOrigin = layerOrigins.values().stream().findFirst().orElseThrow();
		final String stackNumber = firstLayerOrigin.stack();
		final RenderDataClient dataClient = new RenderDataClient(baseDataUrl, owner, firstLayerOrigin.project());

		final String targetStack = stackNumber + targetStackSuffix;
		final StackMetaData targetStackMetaData = dataClient.getStackMetaData(targetStack);
		final Bounds targetBounds = targetStackMetaData.getStackBounds();

		LOG.info("Resaving segmentations from {}/{} to {}/{}", sourceN5, dataset, targetN5, stackNumber);
		final DatasetAttributes attributes = sourceReader.getDatasetAttributes(dataset);
		final RandomAccessibleInterval<UnsignedLongType> segmentations = N5Utils.open(sourceReader, dataset);

		final DatasetAttributes targetAttributes = new DatasetAttributes(
				new long[] {targetBounds.getWidth(), targetBounds.getHeight(), (long) targetStackMetaData.getStackBounds().getDeltaZ()},
				blockSize,
				attributes.getDataType(),
				new GzipCompression());
		try (final N5Writer targetWriter = new N5FSWriter(targetN5)) {
			targetWriter.createDataset(stackNumber, targetAttributes);
		}

		final List<long[][]> grid = Grid.create(targetAttributes.getDimensions(), targetAttributes.getBlockSize());

		final ResolvedTileSpecCollection sourceTiles = dataClient.getResolvedTiles(stackNumber + sourceStackSuffix, null);
		final ResolvedTileSpecCollection targetTiles = dataClient.getResolvedTiles(targetStack, null);
		// Only keep tiles that are in the source stack, since there are no transformations for the others
		targetTiles.retainTileSpecs(sourceTiles.getTileIds());
		targetTiles.recalculateBoundingBoxes();
		final Rectangle remainingStackBounds = targetTiles.toBounds().toRectangle();
		final List<long[][]> relevantGridBlocks = grid.stream()
				.filter(gridBlock -> {
					final long[] blockOffset = gridBlock[0];
					final long[] blockSize = gridBlock[1];
					return remainingStackBounds.intersects(blockOffset[0], blockOffset[1], blockSize[0], blockSize[1]);
				}).collect(Collectors.toList());

		LOG.info("Resaving {} (relevant) of {} (total) blocks", relevantGridBlocks.size(), grid.size());

		final ExecutorService ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() );

		// fuse data block by block
		ex.submit(() -> relevantGridBlocks.parallelStream().forEach(
				gridBlock -> fuseBlock(sourceTiles.getTileIdToSpecMap(), targetTiles.getTileIdToSpecMap(), segmentations, gridBlock, layerOrigins, targetN5, stackNumber))
		);

		try {
			ex.shutdown();
			ex.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
		} catch (final InterruptedException e) {
			throw new RuntimeException("Failed to fuse.", e);
		} finally {
			sourceReader.close();
		}
	}

	private boolean intersects(final long[][] gridBlock, final Bounds bounds) {
		final long[] blockSize = gridBlock[1];
		final long[] blockOffset = gridBlock[0];
		return bounds.toRectangle().intersects(blockOffset[0], blockOffset[1], blockSize[0], blockSize[1]);
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
		final Interval rawBoundingBox = createRawBoundingBox(targetTiles.values().stream().findAny().orElseThrow());
		boolean blockIsEmpty = true;

		for (final Map.Entry<Integer, LayerOrigin> entry : layerOrigins.entrySet()) {
			final int zInExport = entry.getKey();
			final LayerOrigin layerOrigin = entry.getValue();
			// In the stacks, layer 35 was omitted
			final int sectionId = layerOrigin.zLayer();
			final int stackZValue = (sectionId > 34) ? sectionId - 1 : sectionId;
			if (layerOrigin.stack().equals("MISSING")) {
				// Skip the one missing layer
				continue;
			}

			final RandomAccessibleInterval<UnsignedLongType> segmentationLayer = Views.dropSingletonDimensions(Views.hyperSlice(segmentations, 2, zInExport));
			final RandomAccessibleInterval<UnsignedLongType> blockLayer = Views.translate(Views.hyperSlice(blockData, 2, stackZValue), blockOffset[0], blockOffset[1]);

			final List<AffineModel2D> fromTargetTransforms = new ArrayList<>();
			final List<AffineModel2D> toSourceTransforms = new ArrayList<>();

			for (final TileSpec sourceTileSpec : sourceTiles.values()) {
				final TileSpec targetTileSpec = targetTiles.get(sourceTileSpec.getTileId());
				if (targetTileSpec.getZ().intValue() != stackZValue) {
					// We are only interested in the current layer
					continue;
				}

				final TileBounds targetBounds = targetTileSpec.toTileBounds();
				final Interval targetBoundingBox = new FinalInterval(new long[]{targetBounds.getMinX().longValue(), targetBounds.getMinY().longValue()},
																	 new long[]{targetBounds.getMaxX().longValue() - 1, targetBounds.getMaxY().longValue() - 1});
				if (! Intervals.isEmpty(Intervals.intersect(targetBoundingBox, block))) {
					fromTargetTransforms.add(concatenateTransforms(targetTileSpec.getTransformList()).createInverse());
					toSourceTransforms.add(concatenateTransforms(sourceTileSpec.getTransformList()));
				}
			}

			if (fromTargetTransforms.isEmpty()) {
				// No tiles in this block
				continue;
			}

			final Cursor<UnsignedLongType> targetCursor = Views.iterable(blockLayer).localizingCursor();
			final RealRandomAccess<UnsignedLongType> sourceRa = Views.interpolate(Views.extendZero(Views.translate(segmentationLayer, 6250, 6250)), new NearestNeighborInterpolatorFactory<>()).realRandomAccess();
			final double[] currentPoint = new double[targetCursor.numDimensions()];

			while (targetCursor.hasNext()) {
				final UnsignedLongType pixel = targetCursor.next();

				for (int i = 0; i < fromTargetTransforms.size(); ++i) {
					targetCursor.localize(currentPoint);
					fromTargetTransforms.get(i).applyInPlace(currentPoint);

					if (Intervals.contains(rawBoundingBox, new RealPoint(currentPoint))) {
						toSourceTransforms.get(i).applyInPlace(currentPoint);
						sourceRa.setPosition(currentPoint);
						final UnsignedLongType sourcePixel = sourceRa.get();
						if (sourcePixel.get() != 0) {
							pixel.set(sourcePixel);
							blockIsEmpty = false;
							// Take the first hit
							break;
						}
					}
				}
			}
		}

		if (! blockIsEmpty) {
			try (final N5Writer writer = new N5FSWriter(n5path)) {
				N5Utils.saveNonEmptyBlock(blockData, writer, dataset, blockOffset, new UnsignedLongType(0));
			}
		}
	}

	// This uses that the first transform is the scanning correction, which is the same for all tiles
	// Since this is not invertible, we forward-transform the original tile bounds and skip this transform when applying
	// the inverse transforms
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
