package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.solver.visualize.ErrorTools;
import org.janelia.render.client.solver.visualize.ErrorTools.ErrorFilter;
import org.janelia.render.client.solver.visualize.ErrorTools.ErrorType;
import org.janelia.render.client.solver.visualize.ErrorTools.Errors;
import org.janelia.render.client.solver.visualize.VisualizeTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.util.BdvStackSource;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import net.imglib2.multithreading.SimpleMultiThreading;

public class DistributedSolveDebugger extends DistributedSolve
{

	private final int blockIndexToSolve;
	private final String includeTilePatternForAlignedVisualization;

	public DistributedSolveDebugger(
			final SolveSetFactory solveSetFactory,
			final DistributedSolveParameters parameters,
			final int blockIndexToSolve,
			final String includeTilePatternForAlignedVisualization) throws IOException
	{
		super( solveSetFactory, parameters );
		this.blockIndexToSolve = blockIndexToSolve;
		this.includeTilePatternForAlignedVisualization = includeTilePatternForAlignedVisualization;
	}

	@Override
	public List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > distributedSolve()
	{
		//this.solveSet.leftItems.get( 44 ).maxZ = 22100;
		final DistributedSolveWorker< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > w =
				this.solveSet.leftItems.get( blockIndexToSolve ).createWorker(
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
						Double.NaN, // maxRange
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

				// now that solve is done, filter out any unwanted tiles (just for the aligned visualization)
				if (includeTilePatternForAlignedVisualization != null) {
					final Pattern includePattern = Pattern.compile(includeTilePatternForAlignedVisualization);
					final Map<String, AffineModel2D> idToNewModel = s.idToNewModel();
					final Set<String> allTileIds = new HashSet<>(idToNewModel.keySet()); // need key set copy to avoid concurrent mod errors
					final int originalNumberOfTiles = allTileIds.size();
					for (final String tileId : allTileIds) {
						if (! includePattern.matcher(tileId).matches()) {
							idToNewModel.remove(tileId);
						}
					}
					LOG.info("distributedSolve: removed {} tiles from visualization that do not match pattern {}",
							 (originalNumberOfTiles - idToNewModel.size()), includeTilePatternForAlignedVisualization);
				}

				final ImagePlus imp = s.visualizeAligned( 0.5 );
				VisualizeTools.renderBDV( vis, imp, 0.5 );
			}

		} catch ( final Exception e1 )
		{
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

        SimpleMultiThreading.threadHaltUnClean();

		return null;
	}

	public static void main( final String[] args )
	{
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final DistributedSolveParameters parameters = new DistributedSolveParameters();

				int blockIndexToSolve = 0;
				String includeTilePatternForVisualization = null;

                // TODO: remove testing hack ...
                if (args.length == 0) {

					// with blockSize=3 and minZ=1246, blockIndex 1 should contain 1249 and 1250
					blockIndexToSolve = 1;

					// multi-SEM tile ids have pattern <slab>_<mfov>_<sfov>_<scan time>.<z>.0
					//   for example: 001_000006_019_20220407_115555.1247.0
					// pattern 001_000003.* will visualize only tiles in mfov 3
					includeTilePatternForVisualization = "001_000003.*";

                    final String[] testArgs = {
                            "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                            "--owner", "hess",
                            "--project", "wafer_52_cut_00030_to_00039",
                            "--matchCollection", "wafer_52_cut_00030_to_00039_v1",
                            "--stack", "slab_001_all",
                            //"--targetStack", "comment-out-to-prevent-tile-spec-save",
                            //"--completeTargetStack",
                            
                            // note: prealign is with translation only
                            "--blockOptimizerLambdasRigid",       "1.0,1.0,0.9,0.3,0.01",
                            "--blockOptimizerLambdasTranslation", "1.0,0.0,0.0,0.0,0.0",
                            "--blockOptimizerIterations", "100,100,50,25,25",  // "1000,1000,500,250,250", // FlyEM
                            "--blockMaxPlateauWidth", "25,25,15,10,10",        // "250,250,150,100,100",   // FlyEM

                            //"--lambdaStitching", "1.0", // allow rigid tile alignment

                            "--blockSize", "3",
							"--minStitchingInliers", "100000000", // do not stitch first
                            //"--noStitching", // do not stitch first
                            
                            "--minZ", "1246",
                            "--maxZ", "1250",

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

                final SolveSetFactoryAdaptiveRigid solveSetFactory =
                		//new SolveSetFactoryBRSec32(
                		new SolveSetFactoryAdaptiveRigid(
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

                // solveSetFactory.additionalIssues.put( 57325, "problem" );

/*                		new SolveSetFactorySimple(
                				parameters.globalModel(),
                				parameters.blockModel(),
                				parameters.stitchingModel() );*/

                final DistributedSolve solve =
                		new DistributedSolveDebugger(
                				solveSetFactory,
                				parameters,
								blockIndexToSolve,
								includeTilePatternForVisualization );

                solve.run();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveDebugger.class);

}
