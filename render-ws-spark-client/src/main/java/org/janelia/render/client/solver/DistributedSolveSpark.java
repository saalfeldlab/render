package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.util.Pair;

public class DistributedSolveSpark< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends DistributedSolve< G, B, S >
{
	private final JavaSparkContext sparkContext;

	public DistributedSolveSpark(
			final ParametersDistributedSolve parameters,
			final SparkConf sparkConf ) throws IOException
	{
		super( parameters.globalModel(), parameters.blockModel(), parameters.stitchingModel(), parameters );

		this.sparkContext = new JavaSparkContext(sparkConf);

		LogUtilities.logSparkClusterInfo(sparkContext);
	}

	@Override
	public List< SolveItemData< G, B, S > > distributedSolve()
	{
		final long time = System.currentTimeMillis();

		final JavaRDD< SolveItemData< G, B, S > > rddJobs = sparkContext.parallelize( solveSet.allItems() );

		final List< Pair< String, Double > > pGroupList = runParams.pGroupList;
		final Map<String, ArrayList<Double>> sectionIdToZMap = runParams.sectionIdToZMap;

		final String baseDataUrl = parameters.renderWeb.baseDataUrl;
		final String owner = parameters.renderWeb.owner;
		final String project = parameters.renderWeb.project;
		final String matchOwner = parameters.matchOwner;
		final String matchCollection = parameters.matchCollection;

		final double maxAllowedErrorStitching = parameters.maxAllowedErrorStitching;
		final int maxIterationsStitching = parameters.maxIterationsStitching;
		final int maxPlateauWidthStitching = parameters.maxPlateauWidthStitching;
		final List<Double> blockOptimizerLambdasRigid = parameters.blockOptimizerLambdasRigid;
		final List<Double> blockOptimizerLambdasTranslation = parameters.blockOptimizerLambdasTranslation;
		final List<Integer> blockOptimizerIterations = parameters.blockOptimizerIterations;
		final List<Integer> blockMaxPlateauWidth = parameters.blockMaxPlateauWidth;
		final double blockMaxAllowedError = parameters.blockMaxAllowedError;
		final int numThreads = parameters.threadsWorker;
		final String stack = parameters.stack;

		final JavaRDD< List< SolveItemData< G, B, S > > > solvedItems = rddJobs.map(
				solveItemData -> {
					final DistributedSolveWorker< G, B, S > w = new DistributedSolveWorker<>(
							solveItemData,
							pGroupList,
							sectionIdToZMap,
							baseDataUrl,
							owner,
							project,
							matchOwner,
							matchCollection,
							stack,
							maxAllowedErrorStitching,
							maxIterationsStitching,
							maxPlateauWidthStitching,
							blockOptimizerLambdasRigid,
							blockOptimizerLambdasTranslation,
							blockOptimizerIterations,
							blockMaxPlateauWidth,
							blockMaxAllowedError,
							numThreads );
					LogUtilities.setupExecutorLog4j("z " + solveItemData.minZ() + " to " + solveItemData.maxZ());
					w.run();
					return w.getSolveItemDataList();
				});

		final List< List< SolveItemData< G, B, S > > > results = solvedItems.collect();

		final ArrayList< SolveItemData< G, B, S > > allItems = new ArrayList<>();

		for ( final List< SolveItemData< G, B, S > > items : results )
				allItems.addAll( items );

		sparkContext.close();

		LOG.info( "Took: " + ( System.currentTimeMillis() - time )/100 + " sec.");

		return allItems;
	}

	public static void main(final String[] args)
	{
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

				final ParametersDistributedSolve parameters = new ParametersDistributedSolve();
				parameters.parse(args);

				LOG.info("runClient: entry, parameters={}", parameters);

				final SparkConf sparkConf = new SparkConf().setAppName(DistributedSolveSpark.class.getSimpleName());

                @SuppressWarnings({ "rawtypes" })
				final DistributedSolve client = new DistributedSolveSpark(parameters, sparkConf);
				client.run();
            }
        };

        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveSpark.class);

}
