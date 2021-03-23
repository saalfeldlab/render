package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.List;

import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.solver.visualize.ErrorTools;
import org.janelia.render.client.solver.visualize.VisualizeTools;
import org.janelia.render.client.solver.visualize.ErrorTools.ErrorFilter;
import org.janelia.render.client.solver.visualize.ErrorTools.ErrorType;
import org.janelia.render.client.solver.visualize.ErrorTools.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.util.BdvStackSource;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import net.imglib2.multithreading.SimpleMultiThreading;

public class DistributedSolveDebugger extends DistributedSolve
{
	public DistributedSolveDebugger(
			final SolveSetFactory solveSetFactory,
			final DistributedSolveParameters parameters ) throws IOException
	{
		super( solveSetFactory, parameters );
	}

	@Override
	public List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > distributedSolve()
	{
		//this.solveSet.leftItems.get( 44 ).maxZ = 22100;
		final DistributedSolveWorker< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > w =
				this.solveSet.leftItems.get( 20 ).createWorker( //9, 28, 57, 81
						this.solveSet.getMaxId() + 1,
						runParams.pGroupList,
						runParams.sectionIdToZMap,
						parameters.renderWeb.baseDataUrl,
						parameters.renderWeb.owner,
						parameters.renderWeb.project,
						parameters.matchOwner,
						parameters.matchCollection,
						parameters.stack,
						0,
						parameters.serializeMatches,
						parameters.maxAllowedErrorStitching,
						parameters.maxIterationsStitching,
						parameters.maxPlateauWidthStitching,
						parameters.excludeSet(),
						parameters.threadsGlobal );
		try
		{
			w.run();

			new ImageJ();

			for ( final SolveItemData< ?, ?, ? > s : w.getSolveItemDataList() )
			{
				//if ( s.idToNewModel().keySet().size() <= 500 )
				//	continue;
				// visualize maxError
				final Errors errors = ErrorTools.computeErrors( s.idToSolveItemErrorMap(), s.idToTileSpec(), ErrorFilter.CROSS_LAYER_ONLY );
				BdvStackSource<?> vis = ErrorTools.renderErrors( errors, s.idToNewModel(), s.idToTileSpec() );

				vis = ErrorTools.renderPotentialProblemAreas( vis, errors, ErrorType.AVG, 2.0, s.idToNewModel(), s.idToTileSpec() );

				vis = VisualizeTools.renderDynamicLambda( vis, s.zToDynamicLambda(), s.idToNewModel(), s.idToTileSpec(), parameters.dynamicLambdaFactor );

				final ImagePlus imp = s.visualizeAligned();
				VisualizeTools.renderBDV( vis, imp, 0.25 );
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

                final DistributedSolveParameters parameters = new DistributedSolveParameters();

                // TODO: remove testing hack ...
                if (args.length == 0) {
                    final String[] testArgs = {
                            "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                            "--owner", "Z0720_07m_BR", //"flyem", //"cosem", //"Z1217_33m_BR",
                            "--project", "Sec37", //"Z0419_25_Alpha3", //"jrc_hela_2", //"Sec10",
                            "--matchCollection", "Sec37_v1", //"Sec32_v1", //"Z0419_25_Alpha3_v1", //"jrc_hela_2_v1", //"Sec10_multi",
                            "--stack", "v1_acquire_trimmed", //"v3_acquire",
                            //"--targetStack", "v3_acquire_sp1",
                            //"--completeTargetStack",
                            
                            // note: prealign is with translation only
                            "--blockOptimizerLambdasRigid",       "1.0,1.0,0.9,0.3,0.01",
                            "--blockOptimizerLambdasTranslation", "1.0,0.0,0.0,0.0,0.0",
                            "--blockOptimizerIterations", "1000,1000,500,250,250",
                            "--blockMaxPlateauWidth", "250,250,150,100,100",

                            //"--lambdaStitching", "1.0", // allow rigid tile alignment

                            //"--blockSize", "100",
                            //"--noStitching", // do not stitch first
                            
                            //"--minZ", "1",
                            //"--maxZ", "34022",

                            //"--excludeFromRegularization", "1-5,35-534",
                            "--maxNumMatches", "0", // no limit, default
                            "--threadsWorker", "1", 
                            "--threadsGlobal", "8",
                            "--maxPlateauWidthGlobal", "50",
                            "--maxIterationsGlobal", "10000",
							//"--serializerDirectory", "."
							"--dynamicLambdaFactor", "0.0"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final SolveSetFactory solveSetFactory =
                		new SolveSetFactoryAdaptiveRigid(
                		//new SolveSetFactorySimple(
                				parameters.globalModel(),
                				parameters.blockModel(),
                				parameters.stitchingModel(),
                				parameters.blockOptimizerLambdasRigid,
                				parameters.blockOptimizerLambdasTranslation,
                				parameters.blockOptimizerIterations,
                				parameters.blockMaxPlateauWidth,
                				parameters.minStitchingInliers,
                				parameters.blockMaxAllowedError,
                				parameters.dynamicLambdaFactor );

/*                		new SolveSetFactorySimple(
                				parameters.globalModel(),
                				parameters.blockModel(),
                				parameters.stitchingModel() );*/

                final DistributedSolve solve =
                		new DistributedSolveDebugger(
                				solveSetFactory,
                				parameters );

                solve.run();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveDebugger.class);

}
