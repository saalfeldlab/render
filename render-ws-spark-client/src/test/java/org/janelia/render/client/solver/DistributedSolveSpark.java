package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;
import scala.Tuple2;

public class DistributedSolveSpark< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends DistributedSolve< G, B, S >
{
	public DistributedSolveSpark(
			final G globalSolveModel,
			final B blockSolveModel,
			final S stitchingModel,
			final ParametersDistributedSolve parameters ) throws IOException

	{
		super( globalSolveModel, blockSolveModel, stitchingModel, parameters );
	}

	@Override
	public void run( final int setSize )
	{
		final long time = System.currentTimeMillis();

		final int minZ = (int)Math.round( this.runParams.minZ );
		final int maxZ = (int)Math.round( this.runParams.maxZ );

		final SolveSet< G, B, S > solveSet = defineSolveSet( minZ, maxZ, setSize );

		LOG.info( "Defined sets for global solve" );
		LOG.info( "\n" + solveSet );

		final SparkConf conf = new SparkConf().setAppName( getClass().getCanonicalName() );
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel( "ERROR" );

		final JavaRDD< SolveItemData< G, B, S > > rddJobs = sc.parallelize( solveSet.allItems() );

		final List< Pair< String, Double > > pGroupList = runParams.pGroupList;
		final Map<String, ArrayList<Double>> sectionIdToZMap = runParams.sectionIdToZMap;

		final String baseDataUrl = parameters.renderWeb.baseDataUrl;
		final String owner = parameters.renderWeb.owner;
		final String project = parameters.renderWeb.project;
		final String matchOwner = parameters.matchOwner;
		final String matchCollection = parameters.matchCollection;
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
							stack
							);
					w.run();
	
					return w.getSolveItemDataList();
				});

		final List< List< SolveItemData< G, B, S > > > results = solvedItems.collect();

		final ArrayList< SolveItemData< G, B, S > > allItems = new ArrayList<>();

		for ( final List< SolveItemData< G, B, S > > items : results )
				allItems.addAll( items );

		sc.close();

		try
		{
			final GlobalSolve gs = globalSolve( allItems );

			LOG.info( "Took: " + ( System.currentTimeMillis() - time )/100 + " sec.");

			// visualize new result
			new ImageJ();
			ImagePlus imp1 = SolveTools.render( gs.idToFinalModelGlobal, gs.idToTileSpecGlobal, 0.15 );
			imp1.setTitle( "final" );
			SimpleMultiThreading.threadHaltUnClean();

		}
		catch ( NotEnoughDataPointsException | IllDefinedDataPointsException | InterruptedException | ExecutionException | NoninvertibleModelException e )
		{
			e.printStackTrace();
			return;
		}
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
                            "--stack", "v2_py_solve_03_affine_e10_e10_trakem2_22103_15758",
                            //"--targetStack", "v2_py_solve_03_affine_e10_e10_trakem2_22103_15758_new",
                            "--regularizerModelType", "RIGID",
                            "--optimizerLambdas", "1.0, 0.5, 0.1, 0.01",
                            "--minZ", "10000",
                            "--maxZ", "10199",

                            "--threads", "4",
                            "--maxIterations", "10000",
                            "--completeTargetStack",
                            "--matchCollection", "Sec08_patch_matt"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final DistributedSolve< RigidModel2D, InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >, InterpolatedAffineModel2D< RigidModel2D, TranslationModel2D > > solve =
                		new DistributedSolveSpark<>(
                				new RigidModel2D(),
                				new InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >( new AffineModel2D(), new RigidModel2D(), parameters.startLambda ),
                				new InterpolatedAffineModel2D< RigidModel2D, TranslationModel2D >( new RigidModel2D(), new TranslationModel2D(), 0.25 ),
                				parameters );
                
                solve.run( 100 );
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveSpark.class);

}
