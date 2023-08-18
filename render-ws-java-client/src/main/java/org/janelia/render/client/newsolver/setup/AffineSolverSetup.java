package org.janelia.render.client.newsolver.setup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters.PreAlign;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.solver.SerializableValuePair;
import org.janelia.render.client.solver.StabilizingAffineModel2D;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;

public class AffineSolverSetup extends CommandLineParameters
{
	private static final long serialVersionUID = 655629544594300471L;

	public static class RangeConverter implements IStringConverter<SerializableValuePair<Integer, Integer>>
	{
		@Override
		public SerializableValuePair<Integer, Integer> convert( final String value )
		{
			final String[] values = value.split( "-" );

			int a = Integer.parseInt( values[ 0 ] );
			int b = Integer.parseInt( values[ 1 ] );

			if ( b >= a )
				return new SerializableValuePair<>( a, b );
			else
				return new SerializableValuePair<>( b, a );
		}
	}

	@ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

	@ParametersDelegate
	public DistributedSolveParameters distributedSolve = new DistributedSolveParameters();

    @Parameter(
            names = "--stack",
            description = "Stack name",
            required = true)
    public String stack;

    @Parameter(
            names = "--minZ",
            description = "Minimum (split) Z value for layers to be processed")
    public Double minZ;

    @Parameter(
            names = "--maxZ",
            description = "Maximum (split) Z value for layers to be processed")
    public Double maxZ;

    @Parameter(
            names = "--matchOwner",
            description = "Owner of match collection for tiles (default is owner)"
    )
    public String matchOwner;

    @Parameter(
            names = "--matchCollection",
            description = "Name of match collection for tiles",
            required = true
    )
    public String matchCollection;

    //
    // stitching align model, by TRANSLATION with tunable regularization --- with RIGID (default: 0.00)
    //
    @Parameter(
            names = "--lambdaStitching",
            description = "Lambda, used if modelTypeStitchingRegularizer is not null."
    )
    public Double lambdaStitching = 0.0;

    @Parameter(
            names = "--maxAllowedErrorStitching",
            description = "Max allowed error stitching"
    )
    public Double maxAllowedErrorStitching = 10.0;

    @Parameter(
            names = "--maxIterationsStitching",
            description = "Max iterations stitching"
    )
    public Integer maxIterationsStitching = 500;

    @Parameter(
            names = "--maxPlateauWidthStitching",
            description = "Max plateau width stitching"
    )
    public Integer maxPlateauWidthStitching = 50;

    @Parameter(
            names = "--minStitchingInliers",
            description = "how many inliers per tile pair are necessary for 'stitching first'"
    )
    public Integer minStitchingInliers = 25;

    @Parameter(
            names = "--stitchFirst",
            description = "if stitching per z-layer should be performed prior to block alignment (default: false)"
    )
    public boolean stitchFirst = false;

    //
    // alignment of the actual blocks that is performed in parallel
    // models are hardcoded: AFFINE, regularized with RIGID, regularized with Translation, regularized with RegularizingModel (Constant or Stabilizing)
    // and a set of decreasing lambdas (see below)
    //

    @Parameter(
            names = "--preAlign",
            description = "Type of pre-alignment used: NONE, TRANSLATION, RIGID. Note: if you use 'stitchFirst' you must specify TRANSLATION or RIGID (default: none)"
    )
    public PreAlign preAlign = PreAlign.NONE;

    @Parameter(
            names = "--blockOptimizerLambdasRigid",
            description = "Explicit optimizer lambda values for the rigid regularizer, by default optimizer loops through lambdas (1.0,0.5,0.1,0.01)",
            variableArity = true
    )
    public List<Double> blockOptimizerLambdasRigid = new ArrayList<>( Arrays.asList( 1.0, 0.5, 0.1, 0.01 ) );

    @Parameter(
            names = "--blockOptimizerLambdasTranslation",
            description = "Explicit optimizer lambda values for the translation regularizer, by default optimizer loops through lambdas (1.0,0.5,0.1,0.01)",
            variableArity = true
    )
    public List<Double> blockOptimizerLambdasTranslation = new ArrayList<>( Arrays.asList( 0.5, 0.0, 0.0, 0.0 ) );

    @Parameter(
            names = "--blockOptimizerLambdasRegularization",
            description = "Explicit optimizer lambda values for the Regularizer-model, by default optimizer loops through lambdas (0.05, 0.01, 0.0, 0.0)",
            variableArity = true
    )
    public List<Double> blockOptimizerLambdasRegularization = new ArrayList<>( Arrays.asList( 0.05, 0.01, 0.0, 0.0 ) );

    @Parameter(
            names = "--blockOptimizerIterations",
            description = "Explicit num iterations for each lambda value (blockOptimizerLambdas), " +
            			  "by default optimizer uses (1000,1000,400,200), MUST MATCH SIZE of blockOptimizerLambdas",
            variableArity = true
    )
    public List<Integer> blockOptimizerIterations = new ArrayList<>( Arrays.asList( 1000, 1000, 400, 200 ) );

    @Parameter(
            names = "--blockMaxPlateauWidth",
            description = "Explicit max plateau width block alignment for each lambda value (blockOptimizerLambdas), " +
            			  "by default optimizer uses (2500,250,100,50), MUST MATCH SIZE of blockOptimizerLambdas",
            variableArity = true
    )
    public List<Integer> blockMaxPlateauWidth = new ArrayList<>( Arrays.asList( 250, 250, 100, 50 ) );

    @Parameter(
            names = "--blockMaxAllowedError",
            description = "Max allowed error block alignment (default: 10.0)"
    )
    public Double blockMaxAllowedError = 10.0;

    @Parameter(names = "--maxNumMatches", description = "Limit maximum number of matches in between tile pairs (default:0, no limit)")
    public int maxNumMatches = 0;

    @Parameter(names = "--maxZRangeMatches", description = "max z-range in which to load matches (default: '-1' - no limit)")
    public int maxZRangeMatches = -1;

    //
    // for saving and running
    //

    @Parameter(
            names = "--targetOwner",
            description = "Owner name for aligned result stack (default is same as owner)"
    )
    public String targetOwner;

    @Parameter(
            names = "--targetProject",
            description = "Project name for aligned result stack (default is same as project)"
    )
    public String targetProject;

    @Parameter(
            names = "--targetStack",
            description = "Name for aligned result stack (if omitted, aligned models are simply logged)")
    public String targetStack;

    @Parameter(
            names = "--completeTargetStack",
            description = "Complete the target stack after processing",
            arity = 0)
    public boolean completeTargetStack = false;

    @Parameter(names = "--threadsWorker", description = "Number of threads to be used within each worker job (default:1)")
    public int threadsWorker = 1;

    @Parameter(names = "--threadsGlobal", description = "Number of threads to be used for aligning all blocks (default: numProcessors/2)")
    public int threadsGlobal = Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 );

	@Parameter(
			names = "--visualizeResults",
			description = "Visualize results (if running interactively)",
			arity = 0)
	public boolean visualizeResults = false;

	public void initDefaultValues()
	{
		// owner for matches is the same as owner for render, if not specified otherwise
		if ( this.matchOwner == null )
			this.matchOwner = renderWeb.owner;

		// owner for target is the same as owner for render, if not specified otherwise
		if ( this.targetOwner == null )
			this.targetOwner = renderWeb.owner;

		// project for target is the same as project for render, if not specified otherwise
		if ( this.targetProject == null )
			this.targetProject = renderWeb.project;
	}

	public InterpolatedAffineModel2D<InterpolatedAffineModel2D< InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >, TranslationModel2D >, StabilizingAffineModel2D<RigidModel2D>> blockModel()
	{
		if ( this.blockOptimizerIterations.size() != this.blockMaxPlateauWidth.size() || 
				this.blockOptimizerIterations.size() != this.blockOptimizerLambdasRigid.size() ||
				this.blockOptimizerLambdasTranslation.size() != this.blockOptimizerLambdasRigid.size())
			throw new RuntimeException( "Number of entries for blockOptimizerIterations, blockMaxPlateauWidth, blockOptimizerLambdasTranslation and blockOptimizerLambdasRigid not identical." );

		return new InterpolatedAffineModel2D<InterpolatedAffineModel2D< InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >, TranslationModel2D >, StabilizingAffineModel2D<RigidModel2D>>(
						new InterpolatedAffineModel2D< InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >, TranslationModel2D >(
								new InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >(
										new AffineModel2D(),
										new RigidModel2D(), blockOptimizerLambdasRigid.get( 0 ) ),
								new TranslationModel2D(), blockOptimizerLambdasTranslation.get( 0 ) ),
						new StabilizingAffineModel2D<RigidModel2D>( new RigidModel2D() ), 0.0 );
						//new StabilizingAffineModel2D( stitchingModel() ), 0.0 );
						//new ConstantAffineModel2D( stitchingModel() ), 0.0 );
	}

	public InterpolatedAffineModel2D<TranslationModel2D, RigidModel2D> stitchingModel()
	{
		return new InterpolatedAffineModel2D<TranslationModel2D, RigidModel2D>( new TranslationModel2D(), new RigidModel2D(), lambdaStitching );
	}
}
