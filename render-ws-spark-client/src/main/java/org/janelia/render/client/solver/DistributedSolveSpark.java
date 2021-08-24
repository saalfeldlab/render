package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.solver.custom.CustomSolveSetBuilder;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import net.imglib2.util.Pair;

public class DistributedSolveSpark extends DistributedSolve
{
	private final JavaSparkContext sparkContext;

	public DistributedSolveSpark(
			final SolveSetFactory solveSetFactory,
			final DistributedSolveParameters parameters,
			final SparkConf sparkConf ) throws IOException
	{
		super( solveSetFactory, parameters );

		this.sparkContext = new JavaSparkContext(sparkConf);

		LogUtilities.logSparkClusterInfo(sparkContext);
	}

	@Override
	public List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > distributedSolve()
	{
		final long time = System.currentTimeMillis();

		final JavaRDD< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > rddJobs = sparkContext.parallelize( solveSet.allItems() );

		final int startId = solveSet.getMaxId() + 1;
		final List< Pair< String, Double > > pGroupList = runParams.pGroupList;
		final Map<String, ArrayList<Double>> sectionIdToZMap = runParams.sectionIdToZMap;

		final String baseDataUrl = parameters.renderWeb.baseDataUrl;
		final String owner = parameters.renderWeb.owner;
		final String project = parameters.renderWeb.project;
		final String matchOwner = parameters.matchOwner;
		final String matchCollection = parameters.matchCollection;

		final int maxNumMatches = parameters.maxNumMatches;
		final boolean serializeMatches = parameters.serializeMatches;
		final double maxAllowedErrorStitching = parameters.maxAllowedErrorStitching;
		final int maxIterationsStitching = parameters.maxIterationsStitching;
		final int maxPlateauWidthStitching = parameters.maxPlateauWidthStitching;
		final List<Double> blockOptimizerLambdasRigid = parameters.blockOptimizerLambdasRigid;
		final List<Double> blockOptimizerLambdasTranslation = parameters.blockOptimizerLambdasTranslation;
		final List<Integer> blockOptimizerIterations = parameters.blockOptimizerIterations;
		final List<Integer> blockMaxPlateauWidth = parameters.blockMaxPlateauWidth;
		final double blockMaxAllowedError = parameters.blockMaxAllowedError;
		final int numThreads = parameters.threadsWorker;
		final double dynamicLambdaFactor = parameters.dynamicLambdaFactor;
		final HashSet<Integer> excludeFromRegularization = parameters.excludeSet();
		final String stack = parameters.stack;

		final JavaRDD< List< ? extends SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > > solvedItems = rddJobs.map(
				solveItemData -> {
					final DistributedSolveWorker< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > w = //new DistributedSolveWorker<>(
							solveItemData.createWorker(
									startId,
									pGroupList,
									sectionIdToZMap,
									baseDataUrl,
									owner,
									project,
									matchOwner,
									matchCollection,
									stack,
									maxNumMatches,
									serializeMatches,
									maxAllowedErrorStitching,
									maxIterationsStitching,
									maxPlateauWidthStitching,
									Double.NaN, // maxRange
									excludeFromRegularization,
									numThreads );
					LogUtilities.setupExecutorLog4j("z " + solveItemData.minZ() + " to " + solveItemData.maxZ());
					w.run();
					return w.getSolveItemDataList();
				});

		final List< List< ? extends SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > > results = solvedItems.collect();

		final ArrayList< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > allItems = new ArrayList<>();

		for ( final List< ? extends SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > items : results )
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

				final DistributedSolveParameters parameters = new DistributedSolveParameters();
				parameters.parse(args);

				LOG.info("runClient: entry, parameters={}", parameters);

				final SparkConf sparkConf = new SparkConf().setAppName(DistributedSolveSpark.class.getSimpleName());

                final SolveSetFactory solveSetFactory;
                if (parameters.customSolveClass == null) {
					solveSetFactory = new SolveSetFactoryAdaptiveRigid(
																parameters.globalModel(),
																parameters.blockModel(),
																parameters.stitchingModel(), // lambda is 0.0 by default (please double-check)
																parameters.blockOptimizerLambdasRigid,
																parameters.blockOptimizerLambdasTranslation,
																parameters.blockOptimizerIterations,
																parameters.blockMaxPlateauWidth,
																parameters.minStitchingInliers,
																parameters.blockMaxAllowedError,
																parameters.dynamicLambdaFactor);
				} else {
					solveSetFactory = CustomSolveSetBuilder.build(parameters.customSolveClass,
																  parameters.globalModel(),
																  parameters.blockModel(),
																  parameters.stitchingModel(), // lambda is 0.0 by default, might be changed for specific z-layers in custom SolveSetFactories
																  parameters.blockOptimizerLambdasRigid,
																  parameters.blockOptimizerLambdasTranslation,
																  parameters.blockOptimizerIterations,
																  parameters.blockMaxPlateauWidth,
																  parameters.minStitchingInliers,
																  parameters.blockMaxAllowedError,
																  parameters.dynamicLambdaFactor);
				}

				final DistributedSolve client = new DistributedSolveSpark(solveSetFactory, parameters, sparkConf);
				client.run();
            }
        };

        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveSpark.class);

}
