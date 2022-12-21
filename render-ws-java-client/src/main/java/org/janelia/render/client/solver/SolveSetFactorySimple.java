package org.janelia.render.client.solver;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.janelia.render.client.solver.SolveSetFactory.SetInit.Location;

import mpicbg.models.Affine2D;
import mpicbg.trakem2.transform.AffineModel2D;

public class SolveSetFactorySimple extends SolveSetFactory
{
	public SolveSetFactorySimple(
			final Affine2D<?> defaultGlobalSolveModel,
			final Affine2D<?> defaultBlockSolveModel,
			final Affine2D<?> defaultStitchingModel,
			final List<Double> defaultBlockOptimizerLambdasRigid,
			final List<Double> defaultBlockOptimizerLambdasTranslation,
			final List<Integer> defaultBlockOptimizerIterations,
			final List<Integer> defaultBlockMaxPlateauWidth,
			final int defaultMinStitchingInliers,
			final double defaultBlockMaxAllowedError,
			final double defaultDynamicLambdaFactor )
	{
		super(
				defaultGlobalSolveModel,
				defaultBlockSolveModel,
				defaultStitchingModel,
				defaultBlockOptimizerLambdasRigid,
				defaultBlockOptimizerLambdasTranslation,
				defaultBlockOptimizerIterations,
				defaultBlockMaxPlateauWidth,
				defaultMinStitchingInliers,
				defaultBlockMaxAllowedError,
				defaultDynamicLambdaFactor );
	}

	@Override
	public SolveSet defineSolveSet( final int minZ, final int maxZ, final int blockSize, final int minBlockSize, final Map<Integer, String> zToGroupIdMap )
	{
		// left/right set init
		final List< SetInit > initSets = SolveSetFactory.defineSolveSetLayout( minZ, maxZ, blockSize, minBlockSize );

		final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > leftSets = new ArrayList<>();
		final List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > rightSets = new ArrayList<>();

		for ( final SetInit initSet : initSets )
		{
			SolveItemData<? extends Affine2D<?>, ? extends Affine2D<?>, ? extends Affine2D<?>> sid = 
					instantiateSolveItemData(
							initSet.getId(),
							this.defaultGlobalSolveModel,
							this.defaultBlockSolveModel,
							(Function< Integer, Affine2D<?> > & Serializable )(z) -> defaultStitchingModel,
							this.defaultBlockOptimizerLambdasRigid,
							this.defaultBlockOptimizerLambdasTranslation,
							this.defaultBlockOptimizerIterations,
							this.defaultBlockMaxPlateauWidth,
							this.defaultMinStitchingInliers,
							this.defaultBlockMaxAllowedError,
							this.defaultDynamicLambdaFactor,
							false,
							initSet.minZ(),
							initSet.maxZ() );

			if ( initSet.location() == Location.LEFT )
				leftSets.add( sid );
			else
				rightSets.add( sid );
		}

		return new SolveSet( leftSets, rightSets );
	}

	public static void main( String[] args )
	{
		SolveSetFactorySimple setF = new SolveSetFactorySimple(new AffineModel2D(), new AffineModel2D(), new AffineModel2D(), null, null, null, null, 0, 0, 0);
		SolveSet set = setF.defineSolveSet(0, 100, 50, 30, null);

		System.out.println( "Defined sets for global solve" );
		System.out.println( "\n" + set );
	}
	
}
