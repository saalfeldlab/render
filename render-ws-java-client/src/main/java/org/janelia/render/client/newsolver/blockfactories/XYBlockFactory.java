package org.janelia.render.client.newsolver.blockfactories;

import ij.plugin.filter.EDM;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.ResultContainer;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
				.shiftedGrid(In.X, minX, maxX, blockSizeX)
				.shiftedGrid(In.Y, minY, maxY, blockSizeY)
				.singleBlock(In.Z, minZ, maxZ)
				.create();

		// grow blocks such that they overlap
		final List<Bounds> scaledLayout = blockLayout.stream().map(b -> b.scaled(2.0, 2.0, 1.0)).collect(Collectors.toList());
		return blockCollectionFromLayout(scaledLayout, blockSolveParameterProvider);
	}

	@Override
	protected BlockTileBoundsFilter getBlockTileFilter() {
		return BlockTileBoundsFilter.SCALED_XY;
	}

	@Override
	public WeightFunction createWeightFunction(final BlockData<?, ?> block) {
		// the render scale needs to be fairly small so that the entire MFOV area fits into one image
		// TODO: @minnerbe to consider parameterizing scale (so can be set based upon stack bounds)
		return new XYDistanceWeightFunction(block, 0.01);
	}

	static class XYDistanceWeightFunction implements WeightFunction {

		private final Map<Integer, FloatProcessor> layerDistanceMaps;
		private final double resolution;
		private final double minX;
		private final double minY;

		public XYDistanceWeightFunction(final BlockData<?, ?> block, final double resolution) {

			LOG.info("XYDistanceWeightFunction ctor: entry, block {}", block.toDetailsString());

			final ResultContainer<?> results = block.getResults();
			layerDistanceMaps = new HashMap<>(results.getMatchedZLayers().size());
			this.resolution = resolution;

			final Bounds stackBounds = block.getPopulatedBounds();
			minX = stackBounds.getMinX();
			minY = stackBounds.getMinY();

			final Collection<Integer> matchedZLayers = results.getMatchedZLayers();
			if (matchedZLayers.isEmpty()) {
				final List<String> tileIds = results.getTileIds().stream().sorted().collect(Collectors.toList());
				LOG.warn("XYDistanceWeightFunction ctor: block {} results with no matchedZLayers has {} tileIds: {}",
						 block, tileIds.size(), tileIds);
				throw new IllegalStateException("block " + block + " has no matched z layers");
			}

			final ResolvedTileSpecCollection rtsc = results.getResolvedTileSpecs();
			matchedZLayers.forEach(z -> {
				int foundTileCount = 0;
				int missingTileCount = 0;
				final List<TileSpec> layerTiles = new ArrayList<>();
				for (final String tileId: results.getMatchedTileIdsForZLayer(z)) {
					final TileSpec tileSpec = rtsc.getTileSpec(tileId);
					if (tileSpec == null) {
						missingTileCount++;
					} else {
						foundTileCount++;
						layerTiles.add(tileSpec);
					}
				}
				if (layerTiles.isEmpty()) {
					LOG.info("XYDistanceWeightFunction ctor: no tiles found for z {} in {}", z, block.toDetailsString());
				} else {
					layerDistanceMaps.put(z, createLayerDistanceMap(layerTiles, resolution, stackBounds));
				}

				LOG.info("XYDistanceWeightFunction ctor: {} found tiles and {} missing tiles for z {} in {}",
						 foundTileCount, missingTileCount, z, block.toDetailsString());
			});
		}

		public static FloatProcessor createLayerDistanceMap(final List<TileSpec> layerTiles, final double resolution, final Bounds bounds) {

			final String firstTileId = layerTiles.isEmpty() ? null : layerTiles.get(0).getTileId();
			LOG.info("createLayerDistanceMap: entry, firstTileId={}", firstTileId);

			layerTiles.forEach(ts -> ts.replaceFirstChannelImageWithMask(false));

			final RenderParameters renderParameters = new RenderParameters();
			renderParameters.setBounds(bounds);
			renderParameters.setScale(resolution);
			renderParameters.addTileSpecs(layerTiles);
			renderParameters.initializeDerivedValues();

			// TODO: figure out if/how to parameterize cache size
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
			if (distanceMap == null) {
				final List<Integer> mappedZs = layerDistanceMaps.keySet().stream().sorted().collect(Collectors.toList());
				throw new IllegalStateException("failed to find distanceMap for z " + z + ", maps exist for z values " + mappedZs);
			}
			
			return distanceMap.getInterpolatedValue(xLocal, yLocal);
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(XYBlockFactory.class);
}
