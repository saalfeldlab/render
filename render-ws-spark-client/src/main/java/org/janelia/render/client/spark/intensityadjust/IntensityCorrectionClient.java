package org.janelia.render.client.spark.intensityadjust;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.models.AffineModel1D;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.DistributedIntensityCorrectionSolver;
import org.janelia.render.client.newsolver.assembly.AssemblyMaps;
import org.janelia.render.client.newsolver.setup.IntensityCorrectionSetup;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.newsolver.solvers.Worker;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for adjusting same z-layer tile intensities for a stack.
 * Results are stored as tile filters in a result stack.
 *
 * @author Eric Trautman
 */
public class IntensityCorrectionClient
        implements Serializable {

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final IntensityCorrectionSetup cmdLineSetup = new IntensityCorrectionSetup();
                cmdLineSetup.parse(args);
                final IntensityCorrectionClient client = new IntensityCorrectionClient(cmdLineSetup);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final IntensityCorrectionSetup cmdLineSetup;

    public IntensityCorrectionClient(final IntensityCorrectionSetup cmdLineSetup) {
        LOG.info("init: cmdLineSetup={}", cmdLineSetup);
        this.cmdLineSetup = cmdLineSetup;
    }

    public void run() throws IOException {
        final SparkConf conf = new SparkConf().setAppName("IntensityCorrectionClient");
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            runWithContext(sparkContext);
        }
    }

    public void runWithContext(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("runWithContext: entry");

        final RenderSetup renderSetup = RenderSetup.setupSolve(cmdLineSetup);

        final DistributedIntensityCorrectionSolver intensitySolver =
                new DistributedIntensityCorrectionSolver(cmdLineSetup, renderSetup);

        // create all block instances
        final BlockCollection<?, ArrayList<AffineModel1D>, ?> blockCollection = intensitySolver.setupSolve();

        // assign same id to each worker because it is required but not used in this case
        final int hackBlockStartId = blockCollection.maxId() + 1;

        // allow multithread tasks but warn if Spark isn't configured properly to support them
        final int threadsForSparkTasks = cmdLineSetup.distributedSolve.threadsWorker;
        String taskCpusString = null;
        try {
            final SparkConf sparkConf = sparkContext.getConf();
            taskCpusString = sparkConf.get("spark.task.cpus");
        } catch (final Exception e) {
            LOG.warn("ignoring failure to retrieve spark.task.cpus value", e);
        }
        if (taskCpusString != null) {
            final int taskCpus = Integer.parseInt(taskCpusString);
            if (taskCpus != threadsForSparkTasks) {
                LOG.warn("runWithContext: --threadsWorker {} does not match --conf spark.task.cpus={} (you likely want these to be the same)",
                         threadsForSparkTasks, taskCpus);
            }
        }

        final ArrayList<? extends BlockData<?, ArrayList<AffineModel1D>, ?>> blocks = blockCollection.allBlocks();
        final JavaRDD<? extends BlockData<?, ArrayList<AffineModel1D>, ?>> rddBlocks = sparkContext.parallelize(blocks);
        final JavaRDD<ArrayList<? extends BlockData<?, ArrayList<AffineModel1D>, ?>>> rddProcessedBlocks =
                rddBlocks.map(block -> {
                    final Worker<?, ArrayList<AffineModel1D>, ?> worker = block.createWorker(
                            hackBlockStartId,
                            threadsForSparkTasks);
                    worker.run();
                    return worker.getBlockDataList();
                });

        LOG.info("runWithContext: processing {} blocks", blocks.size());

        final List<ArrayList<? extends BlockData<?, ArrayList<AffineModel1D>, ?>>> listOfBlockLists =
                rddProcessedBlocks.collect();

        final List<BlockData<?, ArrayList<AffineModel1D>, ?>> allItems = new ArrayList<>();
        for (final ArrayList<? extends BlockData<?, ArrayList<AffineModel1D>, ?>> blockList: listOfBlockLists) {
            allItems.addAll(blockList);
        }

        // avoid duplicate id assigned while splitting solveitems in the workers
        // but do keep ids that are smaller or equal to the maxId of the initial solveset
        final int maxId = WorkerTools.fixIds(allItems, blockCollection.maxId());

        LOG.info("runWithContext: computed {} blocks, maxId={}", allItems.size(), maxId);

        final AssemblyMaps<ArrayList<AffineModel1D>> finalizedItems = intensitySolver.assembleBlocks(allItems);
        intensitySolver.saveResultsAsNeeded(finalizedItems);

        LOG.info("runWithContext: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityCorrectionClient.class);
}
