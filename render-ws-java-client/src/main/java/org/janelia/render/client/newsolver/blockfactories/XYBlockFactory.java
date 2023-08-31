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

public class XYBlockFactory implements BlockFactory< XYBlockFactory >, Serializable
{
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
	public <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P, XYBlockFactory> defineBlockCollection(
			final ParameterProvider<M, R, P> blockSolveParameterProvider )
	{
		final int[] minXY = new int[] {minX, minY};
		final int[] maxXY = new int[] {maxX, maxY};
		final int[] blockSizeXY = new int[] {blockSizeX, blockSizeY};
		final Map<Integer, List<IntegerInterval>> intervals = IntegerInterval.createOverlappingBlocksND(minXY, maxXY, blockSizeXY);

		// fetch metadata from render
		final BlockDataSolveParameters<?,?,?> basicParameters = blockSolveParameterProvider.basicParameters();
		final RenderDataClient dataClient = new RenderDataClient(
				basicParameters.baseDataUrl(),
				basicParameters.owner(),
				basicParameters.project());

		final ArrayList<BlockData<M, R, P, XYBlockFactory>> blockDataList = new ArrayList<>();
		final IntegerInterval zInterval = new IntegerInterval(minZ, maxZ);

		// for each block, we know the z-range
		intervals.forEach((id, interval) -> {
			final IntegerInterval xInterval = interval.get(0);
			final IntegerInterval yInterval = interval.get(1);
			LOG.info("Try to load block " + id + ": x -> " + xInterval + ", y -> " + yInterval + ", z -> " + zInterval);
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
							(double) minZ,
							(double) maxZ,
							null,//groupId,
							(double) xInterval.min(),
							(double) xInterval.max(),
							(double) yInterval.min(),
							(double) yInterval.max(),
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
				final BlockData<M, R, P, XYBlockFactory> block =
						new BlockData<>(this, blockSolveParameterProvider.create(rtsc), id, rtsc);
				blockDataList.add(block);
			}
		});

		return new BlockCollection<>(blockDataList);
	}

	@Override
	public WeightFunction createWeightFunction(final BlockData<?, ?, ?, XYBlockFactory> block) {
		// the render scale needs to be fairly small so that the entire MFOV area fits into one image
		return new XYDistanceWeightFunction(block, 0.01);
	}

	private static class XYDistanceWeightFunction implements WeightFunction {

		private final Map<Integer, FloatProcessor> layerDistanceMaps;
		private final double resolution;
		private final double minX;
		private final double minY;

		public XYDistanceWeightFunction(final BlockData<?, ?, ?, XYBlockFactory> block, final double resolution) {
			layerDistanceMaps = new HashMap<>(block.zToTileId().size());
			this.resolution = resolution;

			final Pair<double[], double[]> bounds = block.boundingBox();
			minX = bounds.getA()[0];
			minY = bounds.getA()[1];
			final Bounds stackXYBounds = new Bounds(minX, minY, bounds.getB()[0], bounds.getB()[1]);

			block.zToTileId().forEach((z, layerIds) -> {
				final List<TileSpec> layerTiles = layerIds.stream()
						// .sorted() // to be consistent with the render order of the web service
						.map(block.rtsc()::getTileSpec)
						.collect(Collectors.toList());
				layerDistanceMaps.put(z, createLayerDistanceMap(layerTiles, resolution, stackXYBounds));
			});
		}

		public static FloatProcessor createLayerDistanceMap(final List<TileSpec> layerTiles, final double resolution, final Bounds XYBounds) {

			layerTiles.forEach(ts -> ts.replaceFirstChannelImageWithMask(false));

			final RenderParameters renderParameters = new RenderParameters();
			renderParameters.setBounds(XYBounds);
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
