package org.janelia.render.client.newsolver.assembly;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;

public class ResultContainer<M> implements Serializable {

	final private Map<String, M> idToModel = new HashMap<>();
	final private Map<Integer, Set<String>> zToMatchedTileIds = new HashMap<>();
	final private Map<String, Map<String, Double>> idToErrorMap = new HashMap<>();

	// TODO: specifically collected should go into the Parameter objects? We need to make sure each has it's own instance then
	// coefficient-tile intensity average for global intensity-correction
	private final HashMap<String, List<Double>> idToAverages = new HashMap<>();

	final private ResolvedTileSpecCollection rtsc;


	public ResultContainer(final ResolvedTileSpecCollection rtsc) {
		this.rtsc = rtsc;
	}

	public void recordMatchedTile(final int integerZ,
								  final String tileId) {
		final Set<String> matchedTileIdsForZ = zToMatchedTileIds.computeIfAbsent(integerZ, k -> new HashSet<>());
		matchedTileIdsForZ.add(tileId);
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
		return rtsc.getTileIds();
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

}
