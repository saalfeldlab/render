package org.janelia.render.client.newsolver.blockfactories;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.Model;

public interface ParameterProvider < M extends Model< M >, R, P extends BlockDataSolveParameters< M, R, P > >
{
	public P create( ResolvedTileSpecCollection rtsc );
	default public BlockDataSolveParameters< ?,?,? > basicParameters() { return create( null ); }
}
