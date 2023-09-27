package org.janelia.render.client.newsolver.assembly;

import java.io.Serializable;
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

	final public HashMap<String, M> idToModel = new HashMap<>();
	final private HashMap<String, TileSpec> idToTileSpec = new HashMap<>();
	final private HashMap<Integer, HashSet<String>> zToTileId = new HashMap<>();
	final public HashMap<String, List<SerializableValuePair<String, Double>>> idToErrorMap = new HashMap<>();
	final public Set<TransformSpec> sharedTransformSpecs = new HashSet<>();

	public void addTileSpecs(final Collection<TileSpec> tileSpecs) {
		for (final TileSpec tileSpec : tileSpecs) {
			final String tileId = tileSpec.getTileId();
			final Integer z = tileSpec.getZ().intValue();
			idToTileSpec.put(tileId, tileSpec);
			zToTileId.computeIfAbsent(z, k -> new HashSet<>()).add(tileId);
		}
	}

	public Map<String, TileSpec> getIdToTileSpec() {
		return idToTileSpec;
	}

	public Map<Integer, HashSet<String>> getZLayerTileIds() {
		return zToTileId;
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
