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
	final private HashMap<String, TileSpec> idToTileSpec = new HashMap<>();
	final private HashMap<Integer, HashSet<String>> zToTileId = new HashMap<>();
	final private HashMap<String, List<SerializableValuePair<String, Double>>> idToErrorMap = new HashMap<>();
	final private Set<TransformSpec> sharedTransformSpecs = new HashSet<>();

	public void addTileSpecs(final Collection<TileSpec> tileSpecs) {
		for (final TileSpec tileSpec : tileSpecs) {
			final String tileId = tileSpec.getTileId();
			final Integer z = tileSpec.getZ().intValue();
			idToTileSpec.put(tileId, tileSpec);
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
		return idToTileSpec;
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
	 * @return collection built from this assembly's shared transforms and tile specs.
	 */
	public ResolvedTileSpecCollection buildResolvedTileSpecs() {
		return buildResolvedTileSpecs(idToTileSpec.values());
	}

	/**
	 * @return collection built from this assembly's shared transforms and the specified tile specs.
	 */
	public ResolvedTileSpecCollection buildResolvedTileSpecs(final Collection<TileSpec> tileSpecs) {
		final ResolvedTileSpecCollection rtsc = new ResolvedTileSpecCollection(sharedTransformSpecs, tileSpecs);
		rtsc.removeUnreferencedTransforms();
		return rtsc;
	}
}
