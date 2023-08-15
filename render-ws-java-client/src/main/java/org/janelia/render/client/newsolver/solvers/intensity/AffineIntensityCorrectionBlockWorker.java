package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.Affine1D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.IntensityMap8BitFilter;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.intensityadjust.AffineIntensityCorrectionStrategy;
import org.janelia.render.client.intensityadjust.IntensityCorrectionStrategy;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blockfactories.BlockFactory;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMIntensityCorrectionParameters;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class AffineIntensityCorrectionBlockWorker<M extends Model<M> & Affine1D<M>, F extends BlockFactory<F>>
		extends Worker<M, M, FIBSEMIntensityCorrectionParameters<M>, F> {

	private final FIBSEMIntensityCorrectionParameters<M> parameters;

	public AffineIntensityCorrectionBlockWorker(
			final BlockData<M, M, FIBSEMIntensityCorrectionParameters<M>, F> blockData,
			final int startId,
			final int numThreads) throws IOException {
		super(startId, blockData, numThreads);
		parameters = blockData.solveTypeParameters();

		if (parameters.intensityCorrectedFilterStack() != null) {
			final StackMetaData stackMetaData = renderDataClient.getStackMetaData(renderStack);
			renderDataClient.setupDerivedStack(stackMetaData, parameters.intensityCorrectedFilterStack());
		}
	}

	/**
	 * runs the Worker
	 */
	@Override
	public void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException {
		final Map<String, FilterSpec> idToFilterSpec = deriveIntensityFilterData(renderDataClient, blockData.rtsc());

		// this adds the filters to the tile specs and pushes the jata to the DB; don't know if we want that
		// TODO: how do we want to store intensity corrected data?
		//addFilters(blockData.rtsc(), idToFilterSpec);
		//renderDataClient.saveResolvedTiles(blockData.rtsc(), parameters.intensityCorrectedFilterStack(), null);

		LOG.info("AffineIntensityCorrectionBlockWorker: exit, minZ={}, maxZ={}", blockData.minZ(), blockData.maxZ());
	}

	protected Map<String, FilterSpec> deriveIntensityFilterData(
			final RenderDataClient dataClient,
			final ResolvedTileSpecCollection resolvedTiles) throws ExecutionException, InterruptedException, IOException {

		LOG.info("deriveIntensityFilterData: entry");

		if (resolvedTiles.getTileCount() < 2) {
			final String tileCountMsg = resolvedTiles.getTileCount() == 1 ? "1 tile" : "0 tiles";
			LOG.info("deriveIntensityFilterData: skipping correction because collection contains {}", tileCountMsg);
			return null;
		}

		final long maxCachedPixels = parameters.maxNumberOfCachedPixels();
		final ImageProcessorCache imageProcessorCache = (maxCachedPixels == 0)
				? ImageProcessorCache.DISABLED_CACHE
				: new ImageProcessorCache(parameters.maxNumberOfCachedPixels(), true, false);

		final List<MinimalTileSpecWrapper> wrappedTiles = AdjustBlock.wrapTileSpecs(resolvedTiles);
		final IntensityCorrectionStrategy strategy = new AffineIntensityCorrectionStrategy(parameters.lambdaTranslation(), parameters.lambdaIdentity());
		final List<OnTheFlyIntensity> corrected = AdjustBlock.correctIntensitiesForSliceTiles(wrappedTiles,
																							  parameters.renderScale(),
																							  parameters.zDistance(),
																							  imageProcessorCache,
																							  parameters.numCoefficients(),
																							  strategy,
																							  numThreads);

		final Map<String, FilterSpec> idToFilterSpec = new HashMap<>();
		for (final OnTheFlyIntensity onTheFlyIntensity : corrected) {
			final String tileId = onTheFlyIntensity.getMinimalTileSpecWrapper().getTileId();
			final IntensityMap8BitFilter filter = onTheFlyIntensity.toFilter();
			final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(), filter.toParametersMap());
			idToFilterSpec.put(tileId, filterSpec);
		}
		return idToFilterSpec;
	}

	private void addFilters(final ResolvedTileSpecCollection tileSpecs, final Map<String, FilterSpec> idToFilterSpec) {
		for (final Map.Entry<String, FilterSpec> entry : idToFilterSpec.entrySet()) {
			final String tileId = entry.getKey();
			final FilterSpec filterSpec = entry.getValue();
			final TileSpec tileSpec = tileSpecs.getTileSpec(tileId);
			tileSpec.setFilterSpec(filterSpec);
			tileSpec.convertSingleChannelSpecToLegacyForm();
		}
	}

	/**
	 * @return - the result(s) of the solve, multiple ones if they were not connected
	 */
	@Override
	public List<BlockData<M, M, FIBSEMIntensityCorrectionParameters<M>, F>> getBlockDataList() {
		return null;
	}

	private static final Logger LOG = LoggerFactory.getLogger(Worker.class);
}
