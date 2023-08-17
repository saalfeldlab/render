package org.janelia.render.client.newsolver.blockfactories;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.function.Function;

import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.blocksolveparameters.BlockDataSolveParameters;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.Model;

public interface BlockFactory< F extends BlockFactory< F > > extends Serializable
{
	public <M extends Model< M >, R, P extends BlockDataSolveParameters< M, R, P > > BlockCollection< M, R, P, F > defineBlockCollection(
			final ParameterProvider< M, R, P > blockSolveParameterProvider );

	public ArrayList< Function< Double, Double > > createWeightFunctions( final BlockData< ?, ?, ?, F > block );
}
