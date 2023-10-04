package org.janelia.render.client.newsolver.blockfactories;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.assembly.WeightFunction;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.render.client.newsolver.setup.BlockPartitionParameters;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BlockFactory implements Serializable {
	public abstract <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P> defineBlockCollection(
			final ParameterProvider< M, R, P > blockSolveParameterProvider );

	public abstract WeightFunction createWeightFunction(final BlockData<?, ?> block);

	protected abstract ResolvedTileSpecCollection fetchTileSpecs(
			final Bounds bound,
			final RenderDataClient dataClient,
			final BlockDataSolveParameters<?, ?, ?> basicParameters) throws IOException;

	protected <M, R, P extends BlockDataSolveParameters<M, R, P>> BlockCollection<M, R, P> blockCollectionFromLayout(
			final List<Bounds> blockLayout, final ParameterProvider<M, R, P> parameterProvider) {

		final BlockDataSolveParameters<?,?,?> basicParameters = parameterProvider.basicParameters();
		final RenderDataClient dataClient = new RenderDataClient(
				basicParameters.baseDataUrl(),
				basicParameters.owner(),
				basicParameters.project());

		final ArrayList<BlockData<R, P>> blockDataList = new ArrayList<>();

		// fetch metadata from render
		for (final Bounds bound : blockLayout) {
			LOG.info("blockCollectionFromLayout: load block for {}", bound);
			ResolvedTileSpecCollection rtsc;

			try {
				// TODO: trautmane
				rtsc = fetchTileSpecs(bound, dataClient, basicParameters);
			} catch (final Exception e) {
				if (e.getMessage().contains("no tile specifications found"))
					rtsc = null;
				else
					throw new RuntimeException("Failed to fetch data from render. stopping.", e);
			}

			if (rtsc == null || rtsc.getTileCount() == 0) {
				LOG.info("blockCollectionFromLayout: no tiles found, skipping this block");
			} else {
				pruneRtsc(rtsc, bound);
				LOG.info("blockCollectionFromLayout: loaded {} tiles", rtsc.getTileCount());
				final BlockData<R, P> block = new BlockData<>(parameterProvider.create(rtsc),
															  bound,
															  rtsc);
				blockDataList.add(block);
			}
		}

		return new BlockCollection<>(blockDataList);
	}

	protected void pruneRtsc(final ResolvedTileSpecCollection rtsc, final Bounds bound) {
		final Set<String> tileIdsToRemove = new HashSet<>();
		for (final TileSpec ts : rtsc.getTileSpecs()) {
			final Bounds tileBounds = ts.toTileBounds();
			if (!shouldBeIncluded(tileBounds, bound))
				tileIdsToRemove.add(ts.getTileId());
		}
		rtsc.removeTileSpecs(tileIdsToRemove);
	}

	protected abstract boolean shouldBeIncluded(final Bounds tileBounds, final Bounds blockBounds);

	public static BlockFactory fromBlocksizes(final RenderSetup range, final BlockPartitionParameters blockPartition) {

		final int minZ = range.minZ.intValue();
		final int maxZ = range.maxZ.intValue();

		if (blockPartition.hasXY()) {
			final Double minX = range.minX;
			final Double maxX = range.maxX;
			final Double minY = range.minY;
			final Double maxY = range.maxY;

			if (blockPartition.hasZ())
				return new XYZBlockFactory(minX, maxX, minY, maxY, minZ, maxZ, blockPartition.sizeX, blockPartition.sizeY, blockPartition.sizeZ);
			else
				return new XYBlockFactory(minX, maxX, minY, maxY, minZ, maxZ, blockPartition.sizeX, blockPartition.sizeY);
		} else {
			if (blockPartition.hasZ())
				return new ZBlockFactory(minZ, maxZ, blockPartition.sizeZ);
			else
				throw new IllegalArgumentException("At least one of the block sizes in X/Y or Z has to be specified.");
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(BlockFactory.class);
}
