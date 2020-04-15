package org.janelia.render.client.solver.partial;

import java.io.IOException;
import java.util.ArrayList;
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
import org.janelia.render.client.solver.RunParameters;
import org.janelia.render.client.solver.SerializableValuePair;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import net.imglib2.util.Pair;

public class ParametersPartial extends CommandLineParameters
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
            names = "--regularizerModelType",
            description = "Type of model for regularizer",
            required = true
    )
    public ModelType regularizerModelType;

    @Parameter(
            names = "--samplesPerDimension",
            description = "Samples per dimension"
    )
    public Integer samplesPerDimension = 2;

    @Parameter(
            names = "--maxAllowedError",
            description = "Max allowed error"
    )
    public Double maxAllowedError = 200.0;

    @Parameter(
            names = "--maxIterations",
            description = "Max iterations"
    )
    public Integer maxIterations = 10000;

    @Parameter(
            names = "--maxPlateauWidth",
            description = "Max allowed error"
    )
    public Integer maxPlateauWidth = 800;

    @Parameter(
            names = "--startLambda",
            description = "Starting lambda for optimizer.  " +
                          "Optimizer loops through lambdas 1.0, 0.5, 0.1. 0.01.  " +
                          "If you know your starting alignment is good, " +
                          "set this to one of the smaller values to improve performance."
    )
    public Double startLambda = 1.0;

    @Parameter(
            names = "--optimizerLambdas",
            description = "Explicit optimizer lambda values.",
            variableArity = true
    )
    public List<Double> optimizerLambdas;

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
            names = "--mergedZ",
            description = "Z value for all aligned tiles (if omitted, original split z values are kept)"
    )
    public Double mergedZ;

    @Parameter(
            names = "--completeTargetStack",
            description = "Complete the target stack after processing",
            arity = 0)
    public boolean completeTargetStack = false;

    @Parameter(names = "--threads", description = "Number of threads to be used")
    public int numberOfThreads = 1;

    public ParametersPartial() {
    }

    public void initDefaultValues() {

        if (this.matchOwner == null) {
            this.matchOwner = renderWeb.owner;
        }

        if (this.targetOwner == null) {
            this.targetOwner = renderWeb.owner;
        }

        if (this.targetProject == null) {
            this.targetProject = renderWeb.project;
        }
    }

	public static RunParameters setupSolve( final ParametersPartial parameters ) throws IOException
	{
		final RunParameters runParams = new RunParameters();

		parameters.initDefaultValues();

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
