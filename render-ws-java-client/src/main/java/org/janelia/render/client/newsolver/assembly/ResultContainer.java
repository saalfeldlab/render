package org.janelia.render.client.newsolver.assembly;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;

public class ResultContainer<M> implements Serializable {

	private ResolvedTileSpecCollection rtsc;
	private final Map<String, M> idToModel;
	private final Map<Integer, Set<String>> zToMatchedTileIds;
	private final Map<String, Map<String, Double>> idToErrorMap;

	// TODO: specifically collected should go into the Parameter objects? We need to make sure each has it's own instance then
	// coefficient-tile intensity average for global intensity-correction
	private final HashMap<String, List<Double>> idToAverages;

	public ResultContainer() {
		this.rtsc = null;
		this.idToModel = new HashMap<>();
		this.zToMatchedTileIds = new HashMap<>();
		this.idToErrorMap = new HashMap<>();
		this.idToAverages = new HashMap<>();
	}

	/**
	 * Initialize this result with the specified tile spec collection.
	 */
	public void init(final ResolvedTileSpecCollection rtsc) {
		this.rtsc = rtsc;
		this.idToModel.clear();
		this.zToMatchedTileIds.clear();
		this.idToErrorMap.clear();
		this.idToAverages.clear();
	}

	/**
	 * @return true if this result has a tile spec collection with tiles.
	 */
	public boolean isPopulated() {
		return (rtsc != null) && rtsc.hasTileSpecs();
	}

	/**
	 * @return a copy of this result that only contains data for the specified tileIds.
	 */
	public ResultContainer<M> buildSplitResult(final Set<String> withTileIds) {

		if (! this.isPopulated()) {
			throw new IllegalStateException("attempted to split unpopulated results, init() call was probably never made");
		}

		final ResultContainer<M> splitResult = new ResultContainer<>();
		splitResult.init(this.rtsc.copyAndRetainTileSpecs(withTileIds));

		// typically results are split before the tileId maps are populated,
		// so don't waste time splitting the maps if there is nothing in them
		final boolean allTileIdMapsAreEmpty =
				this.idToModel.isEmpty() && this.idToErrorMap.isEmpty() && this.idToAverages.isEmpty();

		if (! allTileIdMapsAreEmpty) {
			// however, future-proof this method by splitting the maps properly if they are populated ...
			for (final String tileId : withTileIds) {
				final M model = this.getModelFor(tileId);
				if (model != null) {
					splitResult.idToModel.put(tileId, model);
				}
				final Map<String, Double> errorMap = this.getErrorMapFor(tileId);
				if (errorMap != null) {
					splitResult.idToErrorMap.put(tileId, errorMap);
				}
				final List<Double> averages = this.getAveragesFor(tileId);
				if (averages != null) {
					splitResult.idToAverages.put(tileId, averages);
				}
			}
		}

		this.zToMatchedTileIds.forEach((z, parentTileIdsForZ) -> {
			final Set<String> tileIdsForZ = parentTileIdsForZ.stream().filter(withTileIds::contains).collect(Collectors.toSet());
			if (! tileIdsForZ.isEmpty()) {
				splitResult.zToMatchedTileIds.put(z, tileIdsForZ);
			}
		});

		return splitResult;
	}

	public void recordMatchedTile(final int integerZ,
								  final String tileId) {
		final Set<String> matchedTileIdsForZ = zToMatchedTileIds.computeIfAbsent(integerZ, k -> new HashSet<>());
		matchedTileIdsForZ.add(tileId);
	}

	/**
	 * Make this result's tileSpec collection consistent with its recorded matched tileIds
	 * by removing all unmatched tileSpecs.
	 *
	 * @return set of tileIds that were removed (or an empty set if none were removed).
	 */
	public Set<String> findAndRemoveUnmatchedTiles() {
		final Set<String> tileIdsBeforeRemoval = new HashSet<>(rtsc.getTileIds());
		final Set<String> matchedTileIds = zToMatchedTileIds.values().stream().flatMap(Collection::stream).collect(Collectors.toSet());

		rtsc.retainTileSpecs(matchedTileIds);

		Set<String> removedTileIds = Collections.emptySet();
		if (tileIdsBeforeRemoval.size() > rtsc.getTileCount()) {
			tileIdsBeforeRemoval.removeAll(rtsc.getTileIds());
			removedTileIds = tileIdsBeforeRemoval;
		}

		return removedTileIds;
	}

	public void recordAllErrors(final String tileId, final Map<String, Double> errorMap) {
		idToErrorMap.put(tileId, errorMap);
	}

	public void recordPairwiseTileError(final String pTileId, final String qTileId, final double error) {
		idToErrorMap.computeIfAbsent(pTileId, k -> new HashMap<>())
				.put(qTileId, error);
		idToErrorMap.computeIfAbsent(qTileId, k -> new HashMap<>())
				.put(pTileId, error);
	}

	public void recordModel(final String tileId, final M model) {
		idToModel.put(tileId, model);
	}

	public void recordAverages(final String tileId,
							   final List<Double> averages) {
		idToAverages.put(tileId, averages);
	}

	public Set<String> getTileIds() {
		return rtsc == null ? new HashSet<>() : rtsc.getTileIds();
	}

	public Set<String> getMatchedTileIdsForZLayer(final int z) {
		return zToMatchedTileIds.get(z);
	}

	public Collection<Integer> getMatchedZLayers() {
		return zToMatchedTileIds.keySet();
	}

	public M getModelFor(final String tileId) {
		return idToModel.get(tileId);
	}

	public Map<String,M> getModelMap() {
		return idToModel;
	}

	public Map<String, Double> getErrorMapFor(final String tileId) {
		return idToErrorMap.get(tileId);
	}

	public List<Double> getAveragesFor(final String tileId) {
		return idToAverages.get(tileId);
	}

	/**
	 * @return collection held by this result-container.
	 */
	public ResolvedTileSpecCollection getResolvedTileSpecs() {
		return rtsc;
	}

	public String toDetailsString() {
		final int tileCount = rtsc == null ? 0 : rtsc.getTileCount();
		return "{\"tileCount\": " + tileCount +
			   ", \"zToMatchedTileIdsSize\": " + zToMatchedTileIds.size() +
			   ", \"idToModelSize\": " + idToModel.size() +
			   ", \"idToErrorMapSize\": " + idToErrorMap.size() +
			   ", \"idToAveragesSize\": " + idToAverages.size() +
			   '}';
	}

}
