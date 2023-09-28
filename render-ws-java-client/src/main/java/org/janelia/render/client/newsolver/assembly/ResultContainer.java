package org.janelia.render.client.newsolver.assembly;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;

public class ResultContainer<M> implements Serializable {

	final private Map<String, M> idToModel = new HashMap<>();
	final private Map<Integer, Set<String>> zToTileId = new HashMap<>();
	final private Map<String, Map<String, Double>> idToErrorMap = new HashMap<>();
	final private ResolvedTileSpecCollection rtsc;


	public ResultContainer(final ResolvedTileSpecCollection rtsc) {
		this.rtsc = rtsc;
		for (final TileSpec tileSpec : rtsc.getTileSpecs()) {
			final String tileId = tileSpec.getTileId();
			final Integer z = tileSpec.getZ().intValue();
			zToTileId.computeIfAbsent(z, k -> new HashSet<>()).add(tileId);
		}
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

	public Set<String> getTileIds() {
		return rtsc.getTileIds();
	}

	public Set<String> getTileIdsForZLayer(final int z) {
		return zToTileId.get(z);
	}

	public Collection<Integer> getZLayers() {
		return zToTileId.keySet();
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

	/**
	 * @return collection held by this result-container.
	 */
	public ResolvedTileSpecCollection getResolvedTileSpecs() {
		return rtsc;
	}

}
