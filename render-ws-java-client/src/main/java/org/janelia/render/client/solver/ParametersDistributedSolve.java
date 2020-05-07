package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ConstantModel;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.transform.RigidModel2D;
import net.imglib2.util.Pair;

public class ParametersDistributedSolve extends CommandLineParameters
{
	private static final long serialVersionUID = 6845718387096692785L;

	@ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

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

    @Parameter(
            names = "--blockSize",
            description = "The size of the blocks in z, which will be computed in paralell (default:500, min:3) "
    )
    public Integer blockSize = 500;

    //
    // not required parameters
    //

    // global block align model, by default RIGID only
    @Parameter(
            names = "--modelTypeGlobal",
            description = "Type of transformation model for the alignment of blocks"
    )
    public ModelType modelTypeGlobal = ModelType.RIGID;

    @Parameter(
            names = "--modelTypeGlobalRegularizer",
            description = "Type of transformation model for regularization for the alignment of blocks"
    )
    public ModelType modelTypeGlobalRegularizer = null;

    @Parameter(
            names = "--lambdaGlobal",
            description = "Lambda, used if modelTypeGlobalRegularizer is not null."
    )
    public Double lambdaGlobal = null;

    @Parameter(
            names = "--maxAllowedErrorGlobal",
            description = "Max allowed error global"
    )
    public Double maxAllowedErrorGlobal = 10.0;

    @Parameter(
            names = "--maxIterationsGlobal",
            description = "Max iterations global"
    )
    public Integer maxIterationsGlobal = 10000;

    @Parameter(
            names = "--maxPlateauWidthGlobal",
            description = "Max plateau width global"
    )
    public Integer maxPlateauWidthGlobal = 500;

    // stitching align model, by default RIGID, regularized with Translation (0.25)
    @Parameter(
            names = "--modelTypeStitching",
            description = "Type of transformation model for section stitching, if null no stitching first"
    )
    public ModelType modelTypeStitching = ModelType.RIGID;

    @Parameter(
            names = "--modelTypeStitchingRegularizer",
            description = "Type of transformation model for regularization for section stitching"
    )
    public ModelType modelTypeStitchingRegularizer = ModelType.TRANSLATION;

    @Parameter(
            names = "--lambdaStitching",
            description = "Lambda, used if modelTypeStitchingRegularizer is not null."
    )
    public Double lambdaStitching = 0.25;

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

    // alignment of the actual blocks that is performed in parallel
    // models are hardcoded: AFFINE, regularized with RIGID, regularized with Translation and a set of decreasing lambdas (see below)
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

    // for saving

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

    @Parameter(names = "--threadsWorker", description = "Number of threads to be used within each worker job (default:1) ")
    public int threadsWorker = 1;

    @Parameter(names = "--threadsGlobal", description = "Number of threads to be used for aligning all blocks (default: numProcessors/2)")
    public int threadsGlobal = Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 );

	@Parameter(
			names = "--serializerDirectory",
			description = "Directory for storing serialized data (omit to skip serialization)")
	public String serializerDirectory;

	public ParametersDistributedSolve() {}

	public void initDefaultValues()
	{
		if ( this.matchOwner == null )
			this.matchOwner = renderWeb.owner;

		if ( this.targetOwner == null )
			this.targetOwner = renderWeb.owner;

		if ( this.targetProject == null )
			this.targetProject = renderWeb.project;
	}

	public < G extends Model< G > & Affine2D< G > > G globalModel()
	{
		if ( this.modelTypeGlobalRegularizer == null )
			return this.modelTypeGlobal.getInstance();
		else
			return (G)(Object)this.modelTypeGlobal.getInterpolatedInstance( modelTypeGlobalRegularizer, lambdaGlobal );
	}

	public < B extends Model< B > & Affine2D< B > > B blockModel()
	{
		if ( this.blockOptimizerIterations.size() != this.blockMaxPlateauWidth.size() || 
				this.blockOptimizerIterations.size() != this.blockOptimizerLambdasRigid.size() ||
				this.blockOptimizerLambdasTranslation.size() != this.blockOptimizerLambdasRigid.size())
			throw new RuntimeException( "Number of entries for blockOptimizerIterations, blockMaxPlateauWidth, blockOptimizerLambdasTranslation and blockOptimizerLambdasRigid not identical." );

		return (B)(Object)
				new InterpolatedAffineModel2D(
						new InterpolatedAffineModel2D(
								new InterpolatedAffineModel2D(
										new AffineModel2D(),
										new RigidModel2D(), blockOptimizerLambdasRigid.get( 0 ) ),
								new TranslationModel2D(), blockOptimizerLambdasTranslation.get( 0 ) ),
						new StabilizingAffineModel2D( new RigidModel2D() ), 0.0 );
						//new StabilizingAffineModel2D( stitchingModel() ), 0.0 );
						//new ConstantAffineModel2D( stitchingModel() ), 0.0 );
	}

	public < S extends Model< S > & Affine2D< S > > S stitchingModel()
	{
		if ( this.modelTypeStitchingRegularizer == null )
			return this.modelTypeStitching.getInstance();
		else
			return (S)(Object)this.modelTypeStitching.getInterpolatedInstance( modelTypeStitchingRegularizer, lambdaStitching );
	}

	public static RunParameters setupSolve( final ParametersDistributedSolve parameters ) throws IOException
	{
		final RunParameters runParams = new RunParameters();

		parameters.initDefaultValues();

		if ( parameters.blockSize < 3 )
			throw new RuntimeException( "Blocksize has to be >= 3." );

		runParams.renderDataClient = parameters.renderWeb.getDataClient();
		runParams.matchDataClient = new RenderDataClient(
				parameters.renderWeb.baseDataUrl,
				parameters.matchOwner,
				parameters.matchCollection);

		runParams.sectionIdToZMap = new TreeMap<>();
		runParams.zToTileSpecsMap = new HashMap<>();
		runParams.totalTileCount = 0;

		if (parameters.targetStack == null)
		{
			runParams.targetDataClient = null;
		}
		else
		{
			runParams.targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl, parameters.targetOwner, parameters.targetProject);

			final StackMetaData sourceStackMetaData = runParams.renderDataClient.getStackMetaData(parameters.stack);
			runParams.targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
		}

		final ZFilter zFilter = new ZFilter(parameters.minZ,parameters.maxZ,null);
		final List<SectionData> allSectionDataList = runParams.renderDataClient.getStackSectionData(parameters.stack, null, null );

		runParams.pGroupList = new ArrayList<>(allSectionDataList.size());

		/*
		runParams.pGroupList.addAll(
				allSectionDataList.stream()
						.filter(sectionData -> zFilter.accept(sectionData.getZ()))
						.map(SectionData::getSectionId)
						.distinct()
						.sorted()
						.collect(Collectors.toList()));
		*/

		final HashMap< String, Double > sectionIds = new HashMap<>();
		for ( final SectionData data : allSectionDataList )
		{
			if ( zFilter.accept( data.getZ() ) )
			{
				final String sectionId = data.getSectionId();
				final double z = data.getZ().doubleValue();

				if ( !sectionIds.containsKey( sectionId ) )
					sectionIds.put( sectionId, z );
			}
		}

		for ( final String entry : sectionIds.keySet() )
			runParams.pGroupList.add( new SerializableValuePair< String, Double >( entry, sectionIds.get( entry ) ) );

		Collections.sort( runParams.pGroupList, new Comparator< Pair< String, Double > >()
		{
			@Override
			public int compare( final Pair< String, Double > o1, final Pair< String, Double > o2 )
			{
				return o1.getA().compareTo( o2.getA() );
			}
		} );

		if (runParams.pGroupList.size() == 0)
			throw new IllegalArgumentException("stack " + parameters.stack + " does not contain any sections with the specified z values");

		Double minZForRun = parameters.minZ;
		Double maxZForRun = parameters.maxZ;

		if ((minZForRun == null) || (maxZForRun == null))
		{
			final StackMetaData stackMetaData = runParams.renderDataClient.getStackMetaData(parameters.stack);
			final StackStats stackStats = stackMetaData.getStats();
			if (stackStats != null)
			{
				final Bounds stackBounds = stackStats.getStackBounds();
				if (stackBounds != null)
				{
					if (minZForRun == null)
						minZForRun = stackBounds.getMinZ();

					if (maxZForRun == null)
						maxZForRun = stackBounds.getMaxZ();
				}
			}

			if ( (minZForRun == null) || (maxZForRun == null) )
				throw new IllegalArgumentException( "Failed to derive min and/or max z values for stack " + parameters.stack + ".  Stack may need to be completed.");
		}

		final Double minZ = minZForRun;
		final Double maxZ = maxZForRun;

		runParams.minZ = minZForRun;
		runParams.maxZ = maxZForRun;

		allSectionDataList.forEach(sd ->
		{
			final Double z = sd.getZ();
			if ((z != null) && (z.compareTo(minZ) >= 0) && (z.compareTo(maxZ) <= 0))
			{
				final List<Double> zListForSection = runParams.sectionIdToZMap.computeIfAbsent(
						sd.getSectionId(), zList -> new ArrayList<>());

				zListForSection.add(sd.getZ());
			}
		});

		return runParams;
	}


}
