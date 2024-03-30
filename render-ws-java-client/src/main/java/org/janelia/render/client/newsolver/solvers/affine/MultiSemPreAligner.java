package org.janelia.render.client.newsolver.solvers.affine;

import mpicbg.models.Affine2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.Model;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.solver.SolveTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Coarsely aligns a multi-SEM stack by treating each mFOV layer as a single tile.
 *
 * @param <M> the model type
 *
 * @author Michael Innerberger
 */
public class MultiSemPreAligner<M extends Model<M> & Affine2D<M>> implements Serializable {

	// Note: this assumes a specific tileId format:
	// <cut>_<mFov>_<sFov>_<day>_<time>.<z>.0
	private static final Pattern TILE_ID_SEPARATOR = Pattern.compile("[_.]");

	private final M model;
	private final double maxAllowedError;
	private final int maxIterations;
	private final int maxPlateauWidth;
	private final int numThreads;
	private final int maxNumMatches;


	public MultiSemPreAligner(
			final M model,
			final double maxAllowedError,
			final int maxIterations,
			final int maxPlateauWidth,
			final int numThreads,
			final int maxNumMatches
	) {
		this.model = model;
		this.maxAllowedError = maxAllowedError;
		this.maxIterations = maxIterations;
		this.maxPlateauWidth = maxPlateauWidth;
		this.numThreads = numThreads;
		this.maxNumMatches = maxNumMatches;
	}

	public Map<String, M> preAlign(
			final ResolvedTileSpecCollection rtsc,
			final List<CanvasMatches> canvasMatches
	) throws IOException, ExecutionException, InterruptedException {

		// initialize and connect models for each mFOV layer
		final Map<String, String> tileIdToMFovLayer = rtsc.getTileSpecs().stream()
				.map(TileSpec::getTileId)
				.collect(Collectors.toMap(
						tileId -> tileId,
						MultiSemPreAligner::extractMFovLayerId
				));
		final Map<String, Tile<M>> mFovLayerToTile = initializeAndConnectTiles(tileIdToMFovLayer, canvasMatches);

		// optimize the models
		final TileConfiguration tileConfig = new TileConfiguration();
		tileConfig.addTiles(mFovLayerToTile.values());

		final DoubleSummaryStatistics errorsBefore = SolveTools.computeErrors(tileConfig.getTiles());
		LOG.info("mFOV layer-wise pre-align: errors after connecting, before optimization: {}", errorsBefore);

		TileUtil.optimizeConcurrently(
				new ErrorStatistic(maxPlateauWidth + 1),
				maxAllowedError,
				maxIterations,
				maxPlateauWidth,
				1.0f,
				tileConfig,
				tileConfig.getTiles(),
				tileConfig.getFixedTiles(),
				numThreads);

		final DoubleSummaryStatistics errorsAfter = SolveTools.computeErrors(tileConfig.getTiles());
		LOG.info("mFOV layer-wise pre-align: errors after optimization: {}", errorsAfter);

		// distribute models to individual tiles
		final Map<String, M> tileIdToModel = new HashMap<>();
		for (final Map.Entry<String, String> tileIdAndMFovLayer : tileIdToMFovLayer.entrySet()) {
			final String tileId = tileIdAndMFovLayer.getKey();
			final String mFovLayer = tileIdAndMFovLayer.getValue();
			final Tile<M> tile = mFovLayerToTile.get(mFovLayer);
			tileIdToModel.put(tileId, tile.getModel());
		}

		return tileIdToModel;
	}

	private Map<String, Tile<M>> initializeAndConnectTiles(
			final Map<String, String> tileIdToMFovLayer,
			final List<CanvasMatches> canvasMatches
	) {
		// initialize models for each mFOV layer
		final Map<String, Tile<M>> mFovLayerToTile = new HashMap<>();
		for (final String mFovLayer : tileIdToMFovLayer.values()) {
			mFovLayerToTile.put(mFovLayer, new Tile<>(model.copy()));
		}

		// accumulate matches for each pair of mFOV layers
		final Map<Pair<Tile<M>, Tile<M>>, List<PointMatch>> pairsToMatches = new HashMap<>();
		for (final CanvasMatches canvasMatch : canvasMatches) {
			final String mFovLayerP = tileIdToMFovLayer.get(canvasMatch.getpId());
			final String mFovLayerQ = tileIdToMFovLayer.get(canvasMatch.getqId());
			if (mFovLayerP.equals(mFovLayerQ)) {
				// only connect tiles from different layers and different mFOVs
				continue;
			}

			final Tile<M> p = mFovLayerToTile.get(mFovLayerP);
			final Tile<M> q = mFovLayerToTile.get(mFovLayerQ);
			final List<PointMatch> layerMatches = pairsToMatches.computeIfAbsent(new ValuePair<>(p, q), k -> new ArrayList<>());

			final List<PointMatch> tileMatches = CanvasMatchResult.convertMatchesToPointMatchList(canvasMatch.getMatches());
			layerMatches.addAll(tileMatches);
		}

		// reduce the number of matches to a maximal number (choose randomly)
		for (final Map.Entry<Pair<Tile<M>, Tile<M>>, List<PointMatch>> entry : pairsToMatches.entrySet()) {
			final Tile<M> p = entry.getKey().getA();
			final Tile<M> q = entry.getKey().getB();
			final List<PointMatch> pointMatches = entry.getValue();
			final List<PointMatch> reducedMatches = getRandomElements(pointMatches, maxNumMatches);
			p.connect(q, reducedMatches);
		}

		return mFovLayerToTile;
	}

	private <T> List<T> getRandomElements(final List<T> list, final int maxEntries) {
		if (list.size() <= maxEntries) {
			return list;
		} else {
			Collections.shuffle(list);
			return list.subList(0, maxEntries);
		}
	}

	private static String extractMFovLayerId(final String tileId) {
		final String[] components = TILE_ID_SEPARATOR.split(tileId);
		final String mFov = components[1];
		final String layer = components[5];
		return mFov + "_" + layer;
	}

	private static final Logger LOG = LoggerFactory.getLogger(MultiSemPreAligner.class);
}
