package org.janelia.render.client.newsolver.assembly;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.client.solver.SerializableValuePair;

public class ResultContainer<M> implements Serializable {

	final private HashMap<String, M> idToModel = new HashMap<>();
	final private HashMap<Integer, HashSet<String>> zToTileId = new HashMap<>();
	final private HashMap<String, List<SerializableValuePair<String, Double>>> idToErrorMap = new HashMap<>();
	final private Set<TransformSpec> sharedTransformSpecs = new HashSet<>();
	final private ResolvedTileSpecCollection rtsc;


	public ResultContainer(final ResolvedTileSpecCollection rtsc) {
		this.rtsc = rtsc;
		for (final TileSpec tileSpec : rtsc.getTileSpecs()) {
			final String tileId = tileSpec.getTileId();
			final Integer z = tileSpec.getZ().intValue();
			zToTileId.computeIfAbsent(z, k -> new HashSet<>()).add(tileId);
		}
	}

	public void recordAllErrors(final String tileId, final List<SerializableValuePair<String, Double>> errorMap) {
		idToErrorMap.put(tileId, errorMap);
	}

	public void recordPairwiseTileError(final String pTileId, final String qTileId, final double error) {
		idToErrorMap.computeIfAbsent(pTileId, k -> new ArrayList<>())
				.add(new SerializableValuePair<>(qTileId, error));
		idToErrorMap.computeIfAbsent(qTileId, k -> new ArrayList<>())
				.add(new SerializableValuePair<>(pTileId, error));
	}

	public void recordModel(final String tileId, final M model) {
		idToModel.put(tileId, model);
	}

	public void addSharedTransforms(final Collection<TransformSpec> transformSpecs) {
		sharedTransformSpecs.addAll(transformSpecs);
	}

	public Map<String, TileSpec> getIdToTileSpec() {
		return rtsc.getTileIdToSpecMap();
	}

	public Map<Integer, HashSet<String>> getZLayerTileIds() {
		return zToTileId;
	}

	public Map<String, M> getIdToModel() {
		return idToModel;
	}

	public Map<String, List<SerializableValuePair<String, Double>>> getIdToErrorMap() {
		return idToErrorMap;
	}

	public Set<TransformSpec> getSharedTransformSpecs() {
		return sharedTransformSpecs;
	}

	/**
	 * @return collection held by this results container.
	 */
	public ResolvedTileSpecCollection getResolvedTileSpecs() {
		return rtsc;
	}
}
