package org.janelia.render.client.newsolver.blockfactories;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.plugin.filter.EDM;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import static org.janelia.render.client.newsolver.blockfactories.BlockLayoutCreator.In;

public class XYBlockFactory extends BlockFactory implements Serializable {

	private static final long serialVersionUID = -2022190797935740332L;

	final int minX, maxX, minY, maxY;
	final int minZ, maxZ;
	final int blockSizeX, blockSizeY;

	public XYBlockFactory(
			final double minX, final double maxX,
			final double minY, final double maxY,
			final int minZ, final int maxZ,
			final int blockSizeX,
			final int blockSizeY)
	{
		this.minX = (int)Math.round(Math.floor( minX ));
		this.maxX = (int)Math.round(Math.ceil( maxX ));
		this.minY = (int)Math.round(Math.floor( minY ));
		this.maxY = (int)Math.round(Math.ceil( maxY ));
		this.minZ = minZ;
		this.maxZ = maxZ;
		this.blockSizeX = blockSizeX;
		this.blockSizeY = blockSizeY;
	}

	@Override
	public <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P> defineBlockCollection(
			final ParameterProvider<M, R, P> blockSolveParameterProvider)
	{
		final List<Bounds> blockLayout = new BlockLayoutCreator()
				.regularGrid(In.X, minX, maxX, blockSizeX)
				.regularGrid(In.Y, minY, maxY, blockSizeY)
				.singleBlock(In.Z, minZ, maxZ)
				.plus()
				.shiftedGrid(In.X, minX, maxX, blockSizeX)
				.shiftedGrid(In.Y, minY, maxY, blockSizeY)
				.singleBlock(In.Z, minZ, maxZ)
				.create();

		return blockCollectionFromLayout(blockLayout, blockSolveParameterProvider);
	}

	@Override
	protected ResolvedTileSpecCollection fetchTileSpecs(
			final Bounds bound,
			final RenderDataClient dataClient,
			final BlockDataSolveParameters<?, ?, ?> basicParameters) throws IOException {

		return dataClient.getResolvedTiles(
				basicParameters.stack(),
				bound.getMinZ(), bound.getMaxZ(),
				null, // groupId,
				bound.getMinX(), bound.getMaxX(),
				bound.getMinY(), bound.getMaxY(),
				null); // matchPattern
	}

	@Override
	protected boolean shouldBeIncluded(final Bounds tileBounds, final Bounds blockBounds) {
		// only keep tiles where midpoint is inside block to reduce overlap
		final Rectangle blockXYBounds = blockBounds.toRectangle();
		return blockXYBounds.contains(tileBounds.getCenterX(), tileBounds.getCenterY());
	}

	@Override
	public WeightFunction createWeightFunction(final BlockData<?, ?> block) {
		// the render scale needs to be fairly small so that the entire MFOV area fits into one image
		return new XYDistanceWeightFunction(block, 0.01);
	}

	static class XYDistanceWeightFunction implements WeightFunction {

		private final Map<Integer, FloatProcessor> layerDistanceMaps;
		private final double resolution;
		private final double minX;
		private final double minY;

		public XYDistanceWeightFunction(final BlockData<?, ?> block, final double resolution) {
			final ResultContainer<?> results = block.getResults();
			layerDistanceMaps = new HashMap<>(results.getZLayers().size());
			this.resolution = resolution;

			final Bounds stackBounds = block.getPopulatedBounds();
			minX = stackBounds.getMinX();
			minY = stackBounds.getMinY();

			results.getZLayers().forEach(z -> {
				final List<TileSpec> layerTiles = results.getTileIdsForZLayer(z).stream()
						// .sorted() // to be consistent with the render order of the web service
						.map(results.getResolvedTileSpecs()::getTileSpec)
						.collect(Collectors.toList());
				layerDistanceMaps.put(z, createLayerDistanceMap(layerTiles, resolution, stackBounds));
			});
		}

		public static FloatProcessor createLayerDistanceMap(final List<TileSpec> layerTiles, final double resolution, final Bounds bounds) {

			layerTiles.forEach(ts -> ts.replaceFirstChannelImageWithMask(false));

			final RenderParameters renderParameters = new RenderParameters();
			renderParameters.setBounds(bounds);
			renderParameters.setScale(resolution);
			renderParameters.addTileSpecs(layerTiles);
			renderParameters.initializeDerivedValues();

			// render the layer into an 8-bit mask: 0=background (no tiles), everything else is foreground (tiles)
			final long pixelsToCache = 100_000_000L;
			final ImageProcessorCache ipCache = new ImageProcessorCache(pixelsToCache, false, false);
			final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
					Renderer.renderImageProcessorWithMasks(renderParameters, ipCache);
			final ByteProcessor renderedLayerMask = ipwm.ip.convertToByteProcessor();

			// compute approximate Euclidean distance map to background (=0) where image border also counts as background
			return (new EDM()).makeFloatEDM(renderedLayerMask, 0, true);
		}

		@Override
		public double compute(final double x, final double y, final double z) {
			// convert to local coordinates of distance map
			final double xLocal = (x - minX) * resolution;
			final double yLocal = (y - minY) * resolution;

			final FloatProcessor distanceMap = layerDistanceMaps.get((int)z);
			return distanceMap.getInterpolatedValue(xLocal, yLocal);
		}
	}
}
