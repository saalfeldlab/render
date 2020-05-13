package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.util.BdvStackSource;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Model;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;

public class DistributedSolveDebugger< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends DistributedSolve< G, B, S >
{
	public DistributedSolveDebugger(
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
		//this.solveSet.leftItems.get( 8 ).maxZ = 4158;
		final DistributedSolveWorker< G, B, S > w = new DistributedSolveWorker<>(
				this.solveSet.leftItems.get( 48 ), //8, 9, 43, 44, 49, 66 ),
				this.solveSet.getMaxId() + 1,
				runParams.pGroupList,
				runParams.sectionIdToZMap,
				parameters.renderWeb.baseDataUrl,
				parameters.renderWeb.owner,
				parameters.renderWeb.project,
				parameters.matchOwner,
				parameters.matchCollection,
				parameters.stack,
				50,
				parameters.serializeMatches,
				parameters.maxAllowedErrorStitching,
				parameters.maxIterationsStitching,
				parameters.maxPlateauWidthStitching,
				parameters.blockOptimizerLambdasRigid,
				parameters.blockOptimizerLambdasTranslation,
				parameters.blockOptimizerIterations,
				parameters.blockMaxPlateauWidth,
				parameters.blockMaxAllowedError,
				parameters.threadsGlobal );
		try
		{
			w.run();

			for ( final SolveItemData< G, B, S > s : w.getSolveItemDataList() )
			{
				final HashMap<String, Float> idToValue = new HashMap<>();

				// visualize dynamic lambda
				for ( final String tileId : s.idToTileSpec().keySet() )
				{
					final int z = (int)Math.round( s.idToTileSpec().get( tileId ).getZ() );
					idToValue.put( tileId, s.zToDynamicLambda().get( z ).floatValue() + 1 ); // between 1 and 1.2
				}

				// visualize maxError
				double minError = Double.MAX_VALUE;
				double maxError = -Double.MAX_VALUE;
				String maxTileId = "";
				for ( final String tileId : s.idToTileSpec().keySet() )
				{
					final double error = s.maxError( tileId );
					minError = Math.min( minError, error );
					if ( error > maxError )
					{
						maxTileId = tileId;
						maxError = error;
					}
					idToValue.put( tileId, (float)error );
				}

				final Pair< HashMap<String, AffineModel2D>, HashMap<String, MinimalTileSpec> > visualizeInfo = 
						VisualizeTools.visualizeInfo( s );
				BdvStackSource<?> vis = VisualizeTools.visualizeMultiRes( visualizeInfo.getA(), visualizeInfo.getB(), idToValue );

				vis.setDisplayRange(0, maxError);
				vis.setDisplayRangeBounds(0, maxError );

				LOG.info( "Min err=" + minError + ", Max err=" + maxError  + " (" + maxTileId + ")" );

				for ( final Pair< String, Double > error : s.idToSolveItemErrorMap.get( maxTileId ) )
					LOG.info( error.getA() + ": " + error.getB() );
			}

			new ImageJ();
			for ( final SolveItemData< G, B, S > s : w.getSolveItemDataList() )
			{
				final ImagePlus imp = s.visualizeAligned();
				VisualizeTools.renderBDV( imp, 0.15 );
			}

		} catch ( Exception e1 )
		{
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

        SimpleMultiThreading.threadHaltUnClean();

		return null;
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
                            "--owner", "Z1217_33m_BR",
                            "--project", "Sec10",
                            "--matchCollection", "Sec10_multi",
                            "--stack", "v3_acquire",
                            //"--targetStack", "v3_acquire_sp1",
                            //"--completeTargetStack",
                            
                            "--blockOptimizerLambdasRigid",       /*"1.0,1.0,0.5,0.1,0.01",*/"1.0,1.0,0.5,0.1,0.01",
                            "--blockOptimizerLambdasTranslation", /*"0.5,0.0,0.0,0.0,0.0",*/"1.0,0.5,0.0,0.0,0.0",
                            "--blockOptimizerIterations", "1000,1000,500,200,100",
                            "--blockMaxPlateauWidth", "250,250,150,100,50",

                            //"--blockSize", "100",
                            //"--noStitching", // do not stitch first
                            
                            "--minZ", "1",
                            "--maxZ", "34022",

                            "--maxNumMatches", "0", // no limit, default
                            "--threadsWorker", "1", 
                            "--threadsGlobal", "65",
                            "--maxPlateauWidthGlobal", "50",
                            "--maxIterationsGlobal", "10000",
							"--serializerDirectory", "."
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                @SuppressWarnings({ "rawtypes", "unchecked" })
                final DistributedSolve solve =
                		new DistributedSolveDebugger(
                				parameters.globalModel(),
                				parameters.blockModel(),
                				parameters.stitchingModel(),
                				parameters );

                solve.run();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveDebugger.class);

}
