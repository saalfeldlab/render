package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ZFilter;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.transform.RigidModel2D;
import net.imglib2.util.Pair;

public class DistributedSolveParameters extends CommandLineParameters
{
	private static final long serialVersionUID = 6845718387096692785L;

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
            description = "Type of transformation model for section stitching (default: Translation2D)"
    )
    public ModelType modelTypeStitching = ModelType.TRANSLATION;

    @Parameter(
            names = "--modelTypeStitchingRegularizer",
            description = "Type of transformation model for regularization for section stitching (default: Rigid2D)"
    )
    public ModelType modelTypeStitchingRegularizer = ModelType.RIGID;

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

    @Parameter(
            names = "--minStitchingInliers",
            description = "how many inliers per tile pair are necessary for 'stitching first'"
    )
    // corresponding with: /groups/flyem/data/alignment/flyem-alignment-ett/Z0720-07m/BR/Sec39/stage_parameters.montage.json
    public Integer minStitchingInliers = 25;

    @Parameter(
            names = "--dynamicLambdaFactor",
            description = "Dynamic lambda varies between 1 (straight sample) and 0 (jitter), this factor scales the dynamic lambda (default: 0.3)"
    )
    public Double dynamicLambdaFactor = 0.3;

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

    @Parameter(names = "--threadsWorker", description = "Number of threads to be used within each worker job (default:1)")
    public int threadsWorker = 1;

    @Parameter(names = "--threadsGlobal", description = "Number of threads to be used for aligning all blocks (default: numProcessors/2)")
    public int threadsGlobal = Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 );

    @Parameter(names = "--maxNumMatches", description = "Limit maximum number of matches in between tile pairs (default:0, no limit)")
    public int maxNumMatches = 0;

    @Parameter(
            names = "--serializeMatches",
            description = "Serialize matches for precise global error ",
            arity = 0)
    public boolean serializeMatches = false;

	@Parameter(
			names = "--serializerDirectory",
			description = "Directory for storing serialized data (omit to skip serialization)")
	public String serializerDirectory;

	@Parameter(
			names = "--excludeFromRegularization",
			converter = RangeConverter.class,
			description = "Exclude certain z sections (including from-to) from dynamic lambda regularization, e.g. 500-550,1000-1100 (default: none)" )
	public List<SerializableValuePair<Integer, Integer>> excludeFromRegularization = new ArrayList<>();

	public DistributedSolveParameters() {}

	public void initDefaultValues()
	{
		if ( this.matchOwner == null )
			this.matchOwner = renderWeb.owner;

		if ( this.targetOwner == null )
			this.targetOwner = renderWeb.owner;

		if ( this.targetProject == null )
			this.targetProject = renderWeb.project;
	}

	public HashSet< Integer > excludeSet()
	{
		final HashSet< Integer > toExclude = new HashSet<>();

		for ( final Pair<Integer, Integer> range : excludeFromRegularization )
			for ( int i = range.getA(); i <= range.getB(); ++i )
				toExclude.add( i );

		return toExclude;
	}

	public Affine2D< ? > globalModel()
	{
		if ( this.modelTypeGlobalRegularizer == null )
			return this.modelTypeGlobal.getInstance();
		else
			return this.modelTypeGlobal.getInterpolatedInstance( modelTypeGlobalRegularizer, lambdaGlobal );
	}

	public Affine2D< ? > blockModel()
	{
		if ( this.blockOptimizerIterations.size() != this.blockMaxPlateauWidth.size() || 
				this.blockOptimizerIterations.size() != this.blockOptimizerLambdasRigid.size() ||
				this.blockOptimizerLambdasTranslation.size() != this.blockOptimizerLambdasRigid.size())
			throw new RuntimeException( "Number of entries for blockOptimizerIterations, blockMaxPlateauWidth, blockOptimizerLambdasTranslation and blockOptimizerLambdasRigid not identical." );

		return	new InterpolatedAffineModel2D(
						new InterpolatedAffineModel2D(
								new InterpolatedAffineModel2D(
										new AffineModel2D(),
										new RigidModel2D(), blockOptimizerLambdasRigid.get( 0 ) ),
								new TranslationModel2D(), blockOptimizerLambdasTranslation.get( 0 ) ),
						new StabilizingAffineModel2D( new RigidModel2D() ), 0.0 );
						//new StabilizingAffineModel2D( stitchingModel() ), 0.0 );
						//new ConstantAffineModel2D( stitchingModel() ), 0.0 );
	}

	public Affine2D< ? > stitchingModel()
	{
		if ( this.modelTypeStitchingRegularizer == null )
			return this.modelTypeStitching.getInstance();
		else
			return this.modelTypeStitching.getInterpolatedInstance( modelTypeStitchingRegularizer, lambdaStitching );
	}

	public static RunParameters setupSolve( final DistributedSolveParameters parameters ) throws IOException
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

		// if minZ || maxZ == null in parameters, then use min and max of the stack
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

			parameters.minZ = minZForRun;
			parameters.maxZ = maxZForRun;
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

		// a HashMap where int is the z section, and string is the description (problem, restart, ...)
		runParams.zToGroupIdMap = new HashMap<>();
		for (final String groupId : Arrays.asList("restart", "problem")) { // NOTE: "problem" groupId is for future use
			LOG.debug( "Querying: " + groupId );
			try {
				final ResolvedTileSpecCollection groupTileSpecs =
						runParams.renderDataClient.getResolvedTiles(parameters.stack,
																	runParams.minZ,
																	runParams.maxZ,
																	groupId,
																	null,
																	null,
																	null,
																	null);
				groupTileSpecs.getTileSpecs().forEach(tileSpec -> runParams.zToGroupIdMap.put(tileSpec.getZ().intValue(), groupId));
			} catch (final IOException t) {
				LOG.info("ignoring failure to retrieve tile specs with groupId '" + groupId + "' (since it's a reasonable thing omitting the exception" );
			}
		}

		final List<Integer> challengeListZ = runParams.zToGroupIdMap.keySet().stream().sorted().collect(Collectors.toList());
		LOG.debug("setup: minZ={}, maxZ={}, challenge layers are {}", (int)Math.round(parameters.minZ), (int)Math.round(parameters.maxZ), challengeListZ);

		return runParams;
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveParameters.class);
}
