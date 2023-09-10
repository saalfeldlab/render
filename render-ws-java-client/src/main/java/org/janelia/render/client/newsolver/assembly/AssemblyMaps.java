package org.janelia.render.client.newsolver.assembly;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.client.solver.SerializableValuePair;

public class AssemblyMaps< M > implements Serializable
{
	final public HashMap<String, M> idToModel = new HashMap<>();
	final public HashMap<String, TileSpec> idToTileSpec = new HashMap<>();
	final public HashMap<Integer, HashSet<String>> zToTileId = new HashMap<>();
	final public HashMap<String, List<SerializableValuePair<String, Double>>> idToErrorMap = new HashMap<>();
	final public Set<TransformSpec> sharedTransformSpecs = new HashSet<>();

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
		final ResolvedTileSpecCollection rtsc = new ResolvedTileSpecCollection(sharedTransformSpecs,
																			   tileSpecs);
		rtsc.removeUnreferencedTransforms();
		return rtsc;
	}
}
