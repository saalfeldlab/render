package org.janelia.render.client.solver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.solver.DistributedSolve.GlobalSolve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;

public class DistributedSolveSparkLocal< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends DistributedSolve< G, B, S >
{
	public DistributedSolveSparkLocal(
			final G globalSolveModel,
			final B blockSolveModel,
			final S stitchingModel,
			final ParametersDistributedSolve parameters ) throws IOException

	{
		super( globalSolveModel, blockSolveModel, stitchingModel, parameters );
	}
	@Override
	public List< SolveItemData< G, B, S > > distributedSolve()
	{
		final long time = System.currentTimeMillis();

		final SparkConf conf = new SparkConf().setAppName( getClass().getCanonicalName() );
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel( "ERROR" );

		final JavaRDD< SolveItemData< G, B, S > > rddJobs = sc.parallelize( solveSet.allItems() );

		final List< Pair< String, Double > > pGroupList = runParams.pGroupList;
		final Map<String, ArrayList<Double>> sectionIdToZMap = runParams.sectionIdToZMap;

		final int startId = solveSet.getMaxId() + 1;

		final String baseDataUrl = parameters.renderWeb.baseDataUrl;
		final String owner = parameters.renderWeb.owner;
		final String project = parameters.renderWeb.project;
		final String matchOwner = parameters.matchOwner;
		final String matchCollection = parameters.matchCollection;

		final int maxNumMatches = parameters.maxNumMatches;
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
							maxAllowedErrorStitching,
							maxIterationsStitching,
							maxPlateauWidthStitching,
							blockOptimizerLambdasRigid,
							blockOptimizerLambdasTranslation,
							blockOptimizerIterations,
							blockMaxPlateauWidth,
							blockMaxAllowedError,
							numThreads );
					w.run();
	
					return w.getSolveItemDataList();
				});

		final List< List< SolveItemData< G, B, S > > > results = solvedItems.collect();

		final ArrayList< SolveItemData< G, B, S > > allItems = new ArrayList<>();

		for ( final List< SolveItemData< G, B, S > > items : results )
				allItems.addAll( items );

		sc.close();

		LOG.info( "Took: " + ( System.currentTimeMillis() - time )/100 + " sec.");

		return allItems;
	}

	public static void main( String[] args )
	{
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final ParametersDistributedSolve parameters = new ParametersDistributedSolve();

                // TODO: remove testing hack ...
                if (args.length == 0) {
                    final String[] testArgs = {
                            "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                            "--owner", "Z1217_19m",
                            "--project", "Sec08",
                            "--matchCollection", "Sec08_patch_matt",
                            "--stack", "v2_py_solve_03_affine_e10_e10_trakem2_22103_15758",
                            //"--targetStack", "v2_py_solve_03_affine_e10_e10_trakem2_22103_15758_new",
                            "--completeTargetStack",
                            
                            "--blockOptimizerLambdasRigid", "1.0,0.5,0.1,0.01",
                            "--blockOptimizerLambdasTranslation", "0.0,0.0,0.0,0.0",
                            "--blockOptimizerIterations", "100,100,40,20",
                            "--blockMaxPlateauWidth", "50,50,50,50",

                            "--blockSize", "100",
                            
                            "--minZ", "10000",
                            "--maxZ", "10199",
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                /*
                final DistributedSolve solve =
                		new DistributedSolveSpark(
                				new RigidModel2D(),
                				new InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >( new AffineModel2D(), new RigidModel2D(), parameters.startLambda ),
                				new InterpolatedAffineModel2D< RigidModel2D, TranslationModel2D >( new RigidModel2D(), new TranslationModel2D(), 0.25 ),
                				parameters );
                
                solve.run();
                */

                DistributedSolve.visualizeOutput = true;
                
                @SuppressWarnings({ "rawtypes", "unchecked" })
				final DistributedSolve solve =
                		new DistributedSolveSparkLocal(
                				parameters.globalModel(),
                				parameters.blockModel(),
                				parameters.stitchingModel(),
                				parameters );
               	solve.run();

                final GlobalSolve gs = solve.globalSolve();

                // visualize the layers
				final HashMap<String, Float> idToValue = new HashMap<>();
				for ( final String tileId : gs.idToTileSpecGlobal.keySet() )
					idToValue.put( tileId, gs.zToDynamicLambdaGlobal.get( (int)Math.round( gs.idToTileSpecGlobal.get( tileId ).getZ() ) ).floatValue() + 1 ); // between 1 and 1.2

                VisualizeTools.visualizeMultiRes( gs.idToFinalModelGlobal, gs.idToTileSpecGlobal, idToValue, 1, 128, 2, parameters.threadsGlobal );

            	SimpleMultiThreading.threadHaltUnClean();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveSpark.class);

}