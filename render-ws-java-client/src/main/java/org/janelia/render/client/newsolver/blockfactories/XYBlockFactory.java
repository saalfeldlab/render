package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;
import java.util.ArrayList;
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
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import net.imglib2.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.render.client.newsolver.blockfactories.BlockLayoutCreator.In;

public class XYBlockFactory implements BlockFactory, Serializable {

	private static final long serialVersionUID = -2022190797935740332L;

	final int minX, maxX, minY, maxY;
	final int minZ, maxZ;
	final int minBlockSizeX, minBlockSizeY;
	final int blockSizeX, blockSizeY;

	public XYBlockFactory(
			final double minX, final double maxX,
			final double minY, final double maxY,
			final int minZ, final int maxZ,
			final int blockSizeX,
			final int blockSizeY,
			final int minBlockSizeX,
			final int minBlockSizeY )
	{
		this.minX = (int)Math.round(Math.floor( minX ));
		this.maxX = (int)Math.round(Math.ceil( maxX ));
		this.minY = (int)Math.round(Math.floor( minY ));
		this.maxY = (int)Math.round(Math.ceil( maxY ));
		this.minZ = minZ;
		this.maxZ = maxZ;
		this.blockSizeX = blockSizeX;
		this.blockSizeY = blockSizeY;
		this.minBlockSizeX = minBlockSizeX;
		this.minBlockSizeY = minBlockSizeY;
	}

	@Override
	public <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P> defineBlockCollection(
			final ParameterProvider<M, R, P> blockSolveParameterProvider )
	{
		final List<Bounds> blockBounds = new BlockLayoutCreator(new int[]{minBlockSizeX, minBlockSizeY, 0})
				.regularGrid(In.X, minX, maxX, blockSizeX)
				.regularGrid(In.Y, minY, maxY, blockSizeY)
				.singleBlock(In.Z, minZ, maxZ)
				.plus()
				.shiftedGrid(In.X, minX, maxX, blockSizeX)
				.shiftedGrid(In.Y, minY, maxY, blockSizeY)
				.singleBlock(In.Z, minZ, maxZ)
				.create();

		// fetch metadata from render
		final BlockDataSolveParameters<?,?,?> basicParameters = blockSolveParameterProvider.basicParameters();
		final RenderDataClient dataClient = new RenderDataClient(
				basicParameters.baseDataUrl(),
				basicParameters.owner(),
				basicParameters.project());

		final ArrayList<BlockData<M, R, P>> blockDataList = new ArrayList<>();

		// for each block, we know the z-range
		int id = 0;
		for (final Bounds bound : blockBounds) {
			LOG.info("Try to load block " + id + ": " + bound);
			ResolvedTileSpecCollection rtsc;

			// TODO: remove debug comments
			// 001_000003_078_20220405_180741.1241.0
			//  "z" : 1241.0,
			//  "minX" : 17849.0,
			//  "maxX" : 19853.0,
			//  "minY" : 10261.0,
			//  "maxY" : 12009.0,
			// should be found in [x: 17747 >>> 35096, LEFT, 1 ------ y: 400 >>> 17585, LEFT, 0]
			// ends up in Block id=1, id=9

			try {
				// TODO: trautmane
				// we fetch all TileSpecs for our z-range
				rtsc = dataClient.getResolvedTiles(
							basicParameters.stack(),
							bound.getMinZ(), bound.getMaxZ(),
							null,//groupId,
							bound.getMinX(), bound.getMaxX(),
							bound.getMinY(), bound.getMaxY(),
							null );// matchPattern
			} catch (final Exception e) {
				if (e.getMessage().contains("no tile specifications found"))
					rtsc = null;
				else
					throw new RuntimeException("Failed to fetch data from render. stopping.", e);
			}

			if (rtsc == null || rtsc.getTileCount() == 0) {
				LOG.info("   Loaded null tiles.");
				LOG.info("Since there are no tiles in this XY block, continuing with next one.");
			} else {
				LOG.info("   Loaded " + rtsc.getTileIds().size() + " tiles.");
				final BlockData<M, R, P> block =
						new BlockData<>(this, blockSolveParameterProvider.create(rtsc), id, rtsc);
				blockDataList.add(block);
				id++;
			}
		}

		return new BlockCollection<>(blockDataList);
	}

	@Override
	public WeightFunction createWeightFunction(final BlockData<?, ?, ?> block) {
		// the render scale needs to be fairly small so that the entire MFOV area fits into one image
		return new XYDistanceWeightFunction(block, 0.01);
	}

	private static class XYDistanceWeightFunction implements WeightFunction {

		private final Map<Integer, FloatProcessor> layerDistanceMaps;
		private final double resolution;
		private final double minX;
		private final double minY;

		public XYDistanceWeightFunction(final BlockData<?, ?, ?> block, final double resolution) {
			layerDistanceMaps = new HashMap<>(block.zToTileId().size());
			this.resolution = resolution;

			final Bounds stackBounds = block.boundingBox();
			minX = stackBounds.getMinX();
			minY = stackBounds.getMinY();

			block.zToTileId().forEach((z, layerIds) -> {
				final List<TileSpec> layerTiles = layerIds.stream()
						// .sorted() // to be consistent with the render order of the web service
						.map(block.rtsc()::getTileSpec)
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

	private static final Logger LOG = LoggerFactory.getLogger(XYBlockFactory.class);
}
