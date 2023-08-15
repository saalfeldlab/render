package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.Affine1D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import net.imglib2.util.ValuePair;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.IntensityMap8BitFilter;
import org.janelia.alignment.match.CanvasMatches;
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
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

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

		// TODO: blockData.idToTileSpec() already resolved?
		final ResolvedTileSpecCollection resolvedTileSpecs = getResolvedTileSpecs();

		// if specified, use match collection to determine patch pairs instead of tile bounds w/distanceZ
		final List<CanvasMatches> tilePairs;
		if (parameters.matchCollection() != null) {
			tilePairs = getMatchPairsFromCollection(parameters.matchCollection());
		} else {
			tilePairs = new ArrayList<>();
		}

		deriveAndStoreIntensityFilterData(renderDataClient, resolvedTileSpecs, tilePairs);
		LOG.info("AffineIntensityCorrectionBlockWorker: exit, minZ={}, maxZ={}", blockData.minZ(), blockData.maxZ());
	}

	private List<CanvasMatches> getMatchPairsFromCollection(final String matchCollection) throws IOException {

		final RenderDataClient matchClient = new RenderDataClient(renderDataClient.getBaseDataUrl(), renderDataClient.getOwner(), matchCollection);
		final List<CanvasMatches> tilePairs = new ArrayList<>();
		final Set<String> alreadyConsidered = new HashSet<>();

		for (final TileSpec tileSpec : blockData.idToTileSpec().values()) {
			final String pGroupId = tileSpec.getLayout().getSectionId();
			final boolean isInBlock = blockData.idToTileSpec().containsKey(pGroupId);
			if (!alreadyConsidered.contains(pGroupId) && isInBlock) {
				for (final CanvasMatches pair : matchClient.getMatchesWithPGroupId(pGroupId, true)) {
					if (blockData.idToTileSpec().containsKey(pair.getqId()))
						tilePairs.add(pair);
				}
				alreadyConsidered.add(pGroupId);
			}
		}
		return tilePairs;
	}

	private ResolvedTileSpecCollection getResolvedTileSpecs() {
		final ResolvedTileSpecCollection resolvedTiles;
		try {
			if (blockData.minZ() == blockData.maxZ()) {
				resolvedTiles = renderDataClient.getResolvedTiles(renderStack, (double) blockData.minZ());
			} else {
				resolvedTiles = renderDataClient.getResolvedTilesForZRange(renderStack, (double) blockData.minZ(), (double) blockData.maxZ());
			}
		} catch (final IOException e) {
			LOG.error("Error getting resolved tiles for stack {} and z range {}-{}", renderStack, blockData.minZ(), blockData.maxZ());
			throw new RuntimeException(e);
		}

		final Set<String> outOfBlockIds = resolvedTiles.getTileIds();
		outOfBlockIds.removeAll(blockData.idToTileSpec().keySet());
		resolvedTiles.removeTileSpecs(outOfBlockIds);

		return resolvedTiles;
	}

	protected void deriveAndStoreIntensityFilterData(
			final RenderDataClient dataClient,
			final ResolvedTileSpecCollection resolvedTiles,
			final List<CanvasMatches> tilePairs) throws ExecutionException, InterruptedException, IOException {

		LOG.info("deriveAndStoreIntensityFilterData: entry");

		if (resolvedTiles.getTileCount() > 1) {
			final long maxCachedPixels = parameters.maxNumberOfCachedPixels();
			final ImageProcessorCache imageProcessorCache = (maxCachedPixels == 0)
					? ImageProcessorCache.DISABLED_CACHE
					: new ImageProcessorCache(parameters.maxNumberOfCachedPixels(), true, false);

			final List<MinimalTileSpecWrapper> wrappedTiles = AdjustBlock.wrapTileSpecs(resolvedTiles);
			final IntensityCorrectionStrategy strategy = new AffineIntensityCorrectionStrategy(parameters.lambdaTranslation(), parameters.lambdaIdentity());
			final List<OnTheFlyIntensity> corrected;

			if (!tilePairs.isEmpty()) {
				final List<ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper>> patchPairs =
						tilePairs.stream()
								.map(tp -> new ValuePair<>(resolvedTiles.getTileSpec(tp.getpId()), resolvedTiles.getTileSpec(tp.getqId())))
								.filter(vp -> (vp.getA() != null) && (vp.getB() != null))
								.map(vp -> new ValuePair<>(new MinimalTileSpecWrapper(vp.getA()), new MinimalTileSpecWrapper(vp.getB())))
								.collect(Collectors.toList());

				corrected = AdjustBlock.correctIntensitiesForPatchPairs(patchPairs,
																		parameters.renderScale(),
																		imageProcessorCache,
																		parameters.numCoefficients(),
																		strategy,
																		numThreads);
			} else {
				corrected = AdjustBlock.correctIntensitiesForSliceTiles(wrappedTiles,
																		parameters.renderScale(),
																		parameters.zDistance(),
																		imageProcessorCache,
																		parameters.numCoefficients(),
																		strategy,
																		numThreads);
			}

			for (final OnTheFlyIntensity onTheFlyIntensity : corrected) {
				final String tileId = onTheFlyIntensity.getMinimalTileSpecWrapper().getTileId();
				final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);
				final IntensityMap8BitFilter filter = onTheFlyIntensity.toFilter();
				final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(), filter.toParametersMap());

				tileSpec.setFilterSpec(filterSpec);
				tileSpec.convertSingleChannelSpecToLegacyForm();
			}
		} else {
			final String tileCountMsg = resolvedTiles.getTileCount() == 1 ? "1 tile" : "0 tiles";
			LOG.info("deriveAndStoreIntensityFilterData: skipping correction because collection contains {}", tileCountMsg);
		}

		dataClient.saveResolvedTiles(resolvedTiles, parameters.intensityCorrectedFilterStack(), null);
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
