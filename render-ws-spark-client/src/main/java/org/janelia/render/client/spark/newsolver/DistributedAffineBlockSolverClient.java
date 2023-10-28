package org.janelia.render.client.spark.newsolver;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import mpicbg.models.AffineModel2D;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.DistributedAffineBlockSolver;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for running a DistributedAffineBlockSolve.
 */
public class DistributedAffineBlockSolverClient
        implements Serializable {

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final AffineBlockSolverSetup parameters = new AffineBlockSolverSetup();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final DistributedAffineBlockSolverClient client = new DistributedAffineBlockSolverClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final AffineBlockSolverSetup cmdLineSetup;

    private DistributedAffineBlockSolverClient(final AffineBlockSolverSetup cmdLineSetup) {
        this.cmdLineSetup = cmdLineSetup;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("DistributedAffineBlockSolverClient");
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            runWithContext(sparkContext);
        }
    }

    public void runWithContext(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("runWithContext: entry, threadsGlobal={}", cmdLineSetup.distributedSolve.threadsGlobal);

        final RenderSetup renderSetup = RenderSetup.setupSolve(cmdLineSetup);
        final DistributedAffineBlockSolver solver = new DistributedAffineBlockSolver(cmdLineSetup, renderSetup);

        final BlockCollection<?, AffineModel2D, ?> blockCollection =
                solver.setupSolve(cmdLineSetup.blockOptimizer.getModel(),
                                  cmdLineSetup.stitching.getModel());
        final List<BlockData<AffineModel2D, ?>> allInputBlocks = new ArrayList<>(blockCollection.allBlocks());

        final JavaRDD<BlockData<AffineModel2D, ?>> rddInputBlocks = sparkContext.parallelize(allInputBlocks);

        final JavaRDD<List<BlockData<AffineModel2D, ?>>> rddOutputBlocks = rddInputBlocks.map(block -> {
            LogUtilities.setupExecutorLog4j(""); // block info is already in most solver log calls so leave context empty here
            return DistributedAffineBlockSolver.createAndRunWorker(block, cmdLineSetup);
        });

        final List<BlockData<AffineModel2D, ?>> allOutputBlocks = rddOutputBlocks
                .collect()
                .stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        if (allOutputBlocks.isEmpty()) {
            throw new IOException("no blocks were computed, something is wrong");
        }

        LOG.info("runWithContext: collected blocks");
        LOG.info("runWithContext: computed {} blocks", allOutputBlocks.size());

        DistributedAffineBlockSolver.solveCombineAndSaveBlocks(cmdLineSetup,
                                                               allOutputBlocks,
                                                               solver);
        LOG.info("runWithContext: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(DistributedAffineBlockSolverClient.class);
}
