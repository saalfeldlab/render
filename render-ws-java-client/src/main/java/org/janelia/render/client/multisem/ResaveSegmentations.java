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
import net.imglib2.realtransform.AffineTransform2D;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
		layerOriginCsv = "layerOrigins.csv";
	}

	public static void main(final String[] args) throws IOException {
		new ResaveSegmentations().run();
	}

	public void run() throws IOException {
		// Create dataset in output N5 container
		final N5Reader sourceReader = new N5FSReader(sourceN5);
		final N5Writer targetWriter = new N5FSWriter(targetN5);

		final Map<Integer, LayerOrigin> layerOrigins = LayerOrigin.getRangeForStack(layerOriginCsv, stackNumber);
		final LayerOrigin firstLayerOrigin = layerOrigins.values().stream().findFirst().orElseThrow();
		final RenderDataClient dataClient = new RenderDataClient(baseDataUrl, owner, firstLayerOrigin.project());

		final String targetStack = firstLayerOrigin.stack() + targetStackSuffix;
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
		targetWriter.createDataset(stackNumber, targetAttributes);
		final Img<UnsignedLongType> output = N5Utils.open(targetWriter, stackNumber);

		final List<long[][]> grid = Grid.create(targetAttributes.getDimensions(), targetAttributes.getBlockSize());

		LOG.info("Resaving {} blocks", grid.size());

		final ResolvedTileSpecCollection sourceTiles = dataClient.getResolvedTiles(firstLayerOrigin.stack() + sourceStackSuffix, null);
		final ResolvedTileSpecCollection targetTiles = dataClient.getResolvedTiles(targetStack, null);

		final ExecutorService ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() );

//		// fuse data block by block
//		ex.submit(() -> grid.parallelStream().forEach(
//				gridBlock -> fuseBlock(sourceTiles.getTileIdToSpecMap(), targetTiles.getTileIdToSpecMap(), segmentations, output, gridBlock))
//		);
		fuseBlock(sourceTiles.getTileIdToSpecMap(), targetTiles.getTileIdToSpecMap(), segmentations, grid.stream().findAny().orElseThrow(), layerOrigins, targetN5, stackNumber);

		try {
			ex.shutdown();
			ex.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
		} catch (final InterruptedException e) {
			throw new RuntimeException("Failed to fuse.", e);
		} finally {
			sourceReader.close();
			targetWriter.close();
		}
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

		for (final Map.Entry<Integer, LayerOrigin> entry : layerOrigins.entrySet()) {
			final int zInExport = entry.getKey();
			final LayerOrigin layerOrigin = entry.getValue();
			// In the stacks, layer 35 was omitted
			final int stackZValue = (layerOrigin.zLayer() > 34) ? layerOrigin.zLayer() - 1 : layerOrigin.zLayer();

			final RandomAccessibleInterval<UnsignedLongType> segmentationLayer = Views.dropSingletonDimensions(Views.hyperSlice(segmentations, 2, zInExport));
			final RandomAccessibleInterval<UnsignedLongType> blockLayer = Views.hyperSlice(blockData, 2, stackZValue);

			final List<Interval> rawBoundingBoxes = new ArrayList<>();
			final List<AffineModel2D> fromTargetTransforms = new ArrayList<>();
			final List<AffineModel2D> toSourceTransforms = new ArrayList<>();
			for (final TileSpec targetTileSpec : targetTiles.values()) {
				if (targetTileSpec.getIntegerZ() != stackZValue) {
					// We are only interested in the current layer
					continue;
				}
				final TileBounds targetBounds = targetTileSpec.toTileBounds();
				final Interval targetBoundingBox = new FinalInterval(new long[]{targetBounds.getMinX().longValue(), targetBounds.getMinY().longValue()},
																	 new long[]{targetBounds.getMaxX().longValue() - 1, targetBounds.getMaxY().longValue() - 1});
				if (! Intervals.isEmpty(Intervals.intersect(targetBoundingBox, block))) {
					rawBoundingBoxes.add(new FinalInterval(new long[]{0, 0}, new long[]{targetTileSpec.getWidth(), targetTileSpec.getHeight()}));
					fromTargetTransforms.add(concatenateTransforms(targetTileSpec.getTransformList()).createInverse());
					toSourceTransforms.add(concatenateTransforms(targetTileSpec.getTransformList()));
				}
			}

			final Cursor<UnsignedLongType> targetCursor = Views.iterable(blockLayer).localizingCursor();
			final RealRandomAccess<UnsignedLongType> sourceRa = Views.interpolate(segmentationLayer, new NearestNeighborInterpolatorFactory<>()).realRandomAccess();
			final double[] currentPoint = new double[targetCursor.numDimensions()];

			while (targetCursor.hasNext()) {
				final UnsignedLongType pixel = targetCursor.next();

				for (int i = 0; i < rawBoundingBoxes.size(); ++i) {
					targetCursor.localize(currentPoint);
					fromTargetTransforms.get(i).applyInPlace(currentPoint);

					if (Intervals.contains(rawBoundingBoxes.get(i), new RealPoint(currentPoint))) {
						toSourceTransforms.get(i).applyInPlace(currentPoint);
						sourceRa.setPosition(currentPoint);
						pixel.set(sourceRa.get());
						// Take the first hit
						break;
					}
				}
			}
		}

		try (final N5Writer writer = new N5FSWriter(n5path)) {
			N5Utils.saveNonEmptyBlock(blockData, writer, dataset, blockOffset, new UnsignedLongType(0));
		}
	}

	/**
	 * Takes two transform lists that realize A->B and A->C and creates a single affine transformation B->C.
	 *
	 * @param transformsAB transform list A->B
	 * @param transformsAC transform list A->C
	 * @return affine transformation B->C
	 */
	private static AffineTransform2D getConnectingTransformation(
			final CoordinateTransformList<CoordinateTransform> transformsAB,
			final CoordinateTransformList<CoordinateTransform> transformsAC
	) {
		final CoordinateTransform trafo1 = transformsAB.get(0);
		final CoordinateTransform trafo2 = transformsAC.get(0);
		if (trafo1.getClass().getName().equals("org.janelia.alignment.transform.ExponentialFunctionOffsetTransform")
				&& trafo2.getClass().getName().equals("org.janelia.alignment.transform.ExponentialFunctionOffsetTransform")) {
			// since we use the same scanning correction as the first step in all stacks, we can just skip
			// the first transform in both stacks (especially since there is no implemented inverse)
			transformsAB.remove(0);
			transformsAC.remove(0);
		}

		final AffineModel2D modelBC = new AffineModel2D();
		final AffineModel2D singleModel = new AffineModel2D();

		// Transformations for B->A
		for (final CoordinateTransform currentTransform : transformsAB.getList(null)) {
			final AbstractAffineModel2D<?> currentModel = ensureAbstractAffineModel2D(currentTransform);
			singleModel.set(currentModel.createInverseAffine());
			modelBC.concatenate(singleModel);
		}

		// Transformations for A->C
		for (final CoordinateTransform currentTransform : transformsAC.getList(null)) {
			final AbstractAffineModel2D<?> currentModel = ensureAbstractAffineModel2D(currentTransform);
			singleModel.set(currentModel.createAffine());
			modelBC.preConcatenate(singleModel);
		}

		final double[][] coefficientsBC = new double[2][3];
		modelBC.toMatrix(coefficientsBC);
		final AffineTransform2D transformAC = new AffineTransform2D();
		transformAC.set(coefficientsBC);
		return transformAC;
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
			// TODO: that's not true
			// since we use the same scanning correction as the first step in all stacks, we can just skip
			// the first transform in both stacks (especially since there is no implemented inverse)
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
