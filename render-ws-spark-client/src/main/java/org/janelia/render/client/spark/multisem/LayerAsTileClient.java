package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.AffineModel2D;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.match.parameters.MatchRunParameters;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.multisem.MFOVAsTileStackClient;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerAsTileParameters;
import org.janelia.render.client.parameter.LayerAsTileStackLists;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.TileRenderParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.match.MultiStagePointMatchClient;
import org.janelia.render.client.spark.newsolver.DistributedAffineBlockSolverClient;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Nonnull;

/**
 * Spark client for ...
 */
public class LayerAsTileClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public LayerAsTileParameters layerAsTile = new LayerAsTileParameters();

        public Parameters() {
        }

        public Parameters(final MultiProjectParameters multiProject,
                          final LayerAsTileParameters layerAsTile) {
            this.multiProject = multiProject;
            this.layerAsTile = layerAsTile;
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final LayerAsTileClient client = new LayerAsTileClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public LayerAsTileClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("run: appId is {}", sparkContext.getConf().getAppId());
            run(sparkContext, clientParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("layerAsTile",
                                                                  pipelineParameters.getLayerAsTile());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {
        final Parameters clientParameters = new Parameters();
        clientParameters.multiProject = pipelineParameters.getMultiProject(pipelineParameters.getRawNamingGroup());
        clientParameters.layerAsTile = pipelineParameters.getLayerAsTile();
        run(sparkContext, clientParameters);
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.RENDER_TILES;
    }

    private void run(final JavaSparkContext sparkContext,
                     final Parameters clientParameters)
            throws IllegalArgumentException, IOException {

        LOG.info("run: entry, clientParameters={}", clientParameters);

        final String baseDataUrl = clientParameters.multiProject.getBaseDataUrl();

        final LayerAsTileStackLists layerAsTileStackLists = new LayerAsTileStackLists(baseDataUrl,
                                                                                      clientParameters.multiProject,
                                                                                      clientParameters.layerAsTile);
        buildDynamicLayerAsTileStacks(sparkContext, layerAsTileStackLists);

        buildRenderedLayerAsTileStacks(sparkContext, layerAsTileStackLists);

        generateLayerAsTileMatches(sparkContext, layerAsTileStackLists);

        alignRenderedLayerAsTileStacks(sparkContext, layerAsTileStackLists);

        buildAlign3DSfovStacks(sparkContext, layerAsTileStackLists);

        LOG.info("run: exit");
    }

    private static void buildDynamicLayerAsTileStacks(final JavaSparkContext sparkContext,
                                                      final LayerAsTileStackLists layerAsTileStackLists) {

        final String baseDataUrl = layerAsTileStackLists.getBaseDataUrl();
        final LayerAsTileParameters layerAsTile = layerAsTileStackLists.getLayerAsTile();
        final double layerRenderScale = layerAsTile.getLayerRenderScale();
        final String dynamicLayerStackSuffix = layerAsTile.getDynamicLayerStackSuffix();

        final List<StackWithZValues> align2DSfovStacksWithAllZ = layerAsTileStackLists.getAlign2DSfovStacksWithAllZ();

        final int parallelism = Math.min(MFOVAsTileClient.MAX_PARTITIONS_FOR_ONE_WEB_SERVER, align2DSfovStacksWithAllZ.size());

        LOG.info("buildDynamicLayerAsTileStacks: entry, distributing build of {} stack(s) with parallelism {} (defaultParallelism={})",
                 align2DSfovStacksWithAllZ.size(), parallelism, sparkContext.defaultParallelism());

        final JavaRDD<StackWithZValues> rddAlign2DSfovStacks = sparkContext.parallelize(align2DSfovStacksWithAllZ,
                                                                                        parallelism);

        final Function<StackWithZValues, StackId> buildLayerStackFunction = stackWithAllZ -> {

            StackId builtStackId = null;

            LogUtilities.setupExecutorLog4j(stackWithAllZ.getStackId().toDevString());

            final StackId align2DStackId = stackWithAllZ.getStackId();
            final StackId dynamicLayerAsTileStackId = align2DStackId.withStackSuffix(dynamicLayerStackSuffix);

            LOG.info("buildLayerStackFunction: entry, prealignedStackId={}, dynamicLayerAsTileStackId={}",
                     align2DStackId.toDevString(), dynamicLayerAsTileStackId.toDevString());

            if (layerAsTileStackLists.isExistingStack(dynamicLayerAsTileStackId)) {
                LOG.info("buildLayerStackFunction: skipping build of {} because it already exists",
                         dynamicLayerAsTileStackId.toDevString());
            } else {
                final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                         align2DStackId.getOwner(),
                                                                         align2DStackId.getProject());

                builtStackId = MFOVAsTileStackClient.buildOneXAsTileStack(stackWithAllZ,
                                                                          dataClient,
                                                                          layerRenderScale,
                                                                          dynamicLayerStackSuffix,
                                                                          false);
            }

            LOG.info("buildLayerStackFunction: exit, prealignedStackId={}, dynamicLayerAsTileStackId={}",
                     align2DStackId.toDevString(), dynamicLayerAsTileStackId.toDevString());

            return builtStackId;
        };

        final JavaRDD<StackId> rddBuiltStacks = rddAlign2DSfovStacks.map(buildLayerStackFunction);
        final List<StackId> builtStacks = rddBuiltStacks.collect();

        final long skippedCount = rddBuiltStacks.filter(Objects::isNull).count();
        final long builtCount = builtStacks.size() - skippedCount;

        LOG.info("buildDynamicLayerAsTileStacks: exit, built {} stack(s), skipped build of {} pre-existing stack(s)",
                 builtCount, skippedCount);
    }

    private static void buildRenderedLayerAsTileStacks(final JavaSparkContext sparkContext,
                                                       final LayerAsTileStackLists layerAsTileStackLists)
            throws IOException {

        LOG.info("buildRenderedLayerAsTileStacks: entry");

        final String baseDataUrl = layerAsTileStackLists.getBaseDataUrl();
        final LayerAsTileParameters layerAsTile = layerAsTileStackLists.getLayerAsTile();
        final String runTimestamp = layerAsTile.getRenderedLayerRunTimestamp();

        final List<JavaRenderTilesClientInfoForLayer> layerClientInfoList = new ArrayList<>();
        final List<StackId> renderedLayerStackList = new ArrayList<>();
        for (final StackWithZValues rawSfovStackWithAllZ : layerAsTileStackLists.getAlign2DSfovStacksWithAllZ()) {

            final StackId rawStackId = rawSfovStackWithAllZ.getStackId();
            final StackId dynamicLayerAsTileStackId = layerAsTile.getDynamicLayerStackId(rawStackId);
            final StackId renderedLayerAsTileStackId = layerAsTile.getRenderedLayerStackId(rawStackId);

            if (layerAsTileStackLists.isExistingStack(renderedLayerAsTileStackId)) {

                LOG.info("buildRenderedLayerAsTileStacks: skipping build of {} because it already exists",
                         renderedLayerAsTileStackId.toDevString());

            } else {

                boolean isSetupNeeded = true;
                for (final Double z : rawSfovStackWithAllZ.getzValues()) {
                    final JavaRenderTilesClientInfoForLayer info =
                            new JavaRenderTilesClientInfoForLayer(baseDataUrl,
                                                                  dynamicLayerAsTileStackId,
                                                                  z,
                                                                  layerAsTile,
                                                                  runTimestamp);
                    layerClientInfoList.add(info);

                    if (isSetupNeeded) {
                        info.setupHackStackAndStorage();
                        isSetupNeeded = false;
                        renderedLayerStackList.add(renderedLayerAsTileStackId);
                    }

                }
            }
        }

        if (! layerClientInfoList.isEmpty()) {

            final int parallelism = Math.min(MFOVAsTileClient.MAX_PARTITIONS_FOR_ONE_WEB_SERVER, layerClientInfoList.size());

            LOG.info("buildRenderedLayerAsTileStacks: distributing rendering for {} layers with parallelism {} (defaultParallelism={})",
                     layerClientInfoList.size(), parallelism, sparkContext.defaultParallelism());

            final JavaRDD<JavaRenderTilesClientInfoForLayer> rddRenderTiles = sparkContext.parallelize(layerClientInfoList,
                                                                                                           parallelism);
            final Function<JavaRenderTilesClientInfoForLayer, Integer> renderTilesFunction = JavaRenderTilesClientInfoForLayer::renderTiles;
            final JavaRDD<Integer> rddRenderedTileCounts = rddRenderTiles.map(renderTilesFunction);

            final List<Integer> resultList = rddRenderedTileCounts.collect();

            LOG.info("buildRenderedLayerAsTileStacks: completed rendering for {} layer tiles", resultList.size());

            for (final StackId hackStackId : renderedLayerStackList) {
                final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                         hackStackId.getOwner(),
                                                                         hackStackId.getProject());
                dataClient.setStackState(hackStackId.getStack(), StackMetaData.StackState.COMPLETE);
            }
        }

        LOG.info("buildRenderedLayerAsTileStacks: exit");
    }

    private static void generateLayerAsTileMatches(final JavaSparkContext sparkContext,
                                                   final LayerAsTileStackLists layerAsTileStackLists)
            throws IOException {

        LOG.info("generateLayerAsTileMatches: entry");

        final String baseDataUrl = layerAsTileStackLists.getBaseDataUrl();
        final List<MatchRunParameters> layerMatchRunList = layerAsTileStackLists.getLayerAsTile().buildLayerMatchRunList();

        for (final String owner : layerAsTileStackLists.getOwners()) {

            final RenderDataClient renderDataClient = new RenderDataClient(baseDataUrl, owner, "not_used");
            final Set<String> existingMatchCollectionNames = renderDataClient.getOwnerMatchCollections().stream()
                    .map(mcmd -> mcmd.getCollectionId().getName())
                    .collect(Collectors.toSet());

            for (final String project : layerAsTileStackLists.getProjectsWithOwner(owner)) {

                final MultiStagePointMatchClient matchClient = new MultiStagePointMatchClient();

                final List<String> projectStackNameList = new ArrayList<>();
                final List<StackWithZValues> listOfRenderedLayerStackLayersInProject = new ArrayList<>();

                for (final StackWithZValues stackWithZ : layerAsTileStackLists.getRenderedLayerStacksWithAllZ(owner, project)) {

                    final StackId stackId = stackWithZ.getStackId();
                    final String matchCollectionName = stackId.getDefaultMatchCollectionId(false).getName();

                    if (existingMatchCollectionNames.contains(matchCollectionName)) {
                        LOG.info("generateLayerAsTileMatches: skipping {} match generation because it already exists",
                                 matchCollectionName);
                    } else {
                        projectStackNameList.add(stackId.getStack());
                        for (final Double z : stackWithZ.getzValues()) {
                            listOfRenderedLayerStackLayersInProject.add(new StackWithZValues(stackId,
                                                                                             Collections.singletonList(z)));
                        }
                    }
                }

                if (! projectStackNameList.isEmpty()) {

                    LOG.info("generateLayerAsTileMatches: starting generation for project {} with stacks {}",
                             project, projectStackNameList);

                    final MultiProjectParameters multiProject = new MultiProjectParameters();
                    multiProject.baseDataUrl = baseDataUrl;
                    multiProject.owner = owner;
                    multiProject.project = project;
                    multiProject.stackIdWithZ.stackNames = projectStackNameList;

                    matchClient.generatePairsAndMatchesForRunList(sparkContext,
                                                                  multiProject,
                                                                  listOfRenderedLayerStackLayersInProject,
                                                                  layerMatchRunList);
                }

            }
        }

        LOG.info("generateLayerAsTileMatches: exit");
    }

    private static void alignRenderedLayerAsTileStacks(final JavaSparkContext sparkContext,
                                                       final LayerAsTileStackLists layerAsTileStackLists)
            throws IOException {

        LOG.info("alignRenderedLayerAsTileStacks: entry");

        final String baseDataUrl = layerAsTileStackLists.getBaseDataUrl();
        final LayerAsTileParameters layerAsTile = layerAsTileStackLists.getLayerAsTile();
        final AffineBlockSolverSetup affineSetup = layerAsTile.buildLayerAffineBlockSolverSetup();

        final boolean deriveMatchCollectionNamesFromProject = false; // use standard stack-based match collection names
        final String matchSuffix = "";                               // without any suffix

        final List<AffineBlockSolverSetup> setupList = new ArrayList<>();
        for (final StackWithZValues renderedLayerStackWithAllZ : layerAsTileStackLists.getRenderedLayerStacksWithAllZ()) {

            final StackId renderedLayerStackId = renderedLayerStackWithAllZ.getStackId();
            final StackId alignedLayerStackId =
                    renderedLayerStackId.withStackSuffix(layerAsTile.getAlignedLayerStackSuffix());

            if (layerAsTileStackLists.isExistingStack(alignedLayerStackId)) {
                LOG.info("alignRenderedLayerAsTileStacks: skipping alignment of {} because {} already exists",
                         renderedLayerStackId.toDevString(), alignedLayerStackId.toDevString());
            } else {
                setupList.add(affineSetup.buildPipelineClone(baseDataUrl,
                                                             renderedLayerStackWithAllZ,
                                                             deriveMatchCollectionNamesFromProject,
                                                             matchSuffix));
            }
        }

        if (! setupList.isEmpty()) {
            LOG.info("alignRenderedLayerAsTileStacks: distributing alignment of {} stack(s)", setupList.size());
            final DistributedAffineBlockSolverClient affineBlockSolverClient = new DistributedAffineBlockSolverClient();
            affineBlockSolverClient.alignSetupList(sparkContext, setupList);
        }

        LOG.info("alignRenderedLayerAsTileStacks: exit");
    }

    private static void buildAlign3DSfovStacks(final JavaSparkContext sparkContext,
                                               final LayerAsTileStackLists layerAsTileStackLists) {

        LOG.info("buildAlign3DSfovStacks: entry");

        final String baseDataUrl = layerAsTileStackLists.getBaseDataUrl();
        final LayerAsTileParameters layerAsTile = layerAsTileStackLists.getLayerAsTile();
        final String align3DSfovStackSuffix = layerAsTile.getAlign3DSfovStackSuffix();
        final String renderedLayerStackSuffix = layerAsTile.getRenderedLayerStackSuffixForRawSfovStack();
        final String alignedLayerStackSuffixForRaw = layerAsTile.getAlignedLayerStackSuffixForRawSfovStack();

        final List<StackWithZValues> rawSfovStacksWithAllZ = layerAsTileStackLists.getAlign2DSfovStacksWithAllZ();
        final List<StackWithZValues> align3DSfovStacksWithAllZ = layerAsTileStackLists.getAlign3DSfovStacksWithAllZ();

        final List<StackWithZValues> rawSfovStacksNeedingAlign3DStack = new ArrayList<>();

        for (int i = 0; i < align3DSfovStacksWithAllZ.size(); i++) {
            final StackWithZValues align3DSfovStackWithAllZ = align3DSfovStacksWithAllZ.get(i);
            final StackId align3DSfovStackId = align3DSfovStackWithAllZ.getStackId();
            if (layerAsTileStackLists.isExistingStack(align3DSfovStackId)) {
                LOG.info("buildAlign3DSfovStacks: skipping creation of {} because it already exists",
                         align3DSfovStackId.toDevString());
            } else {
                rawSfovStacksNeedingAlign3DStack.add(rawSfovStacksWithAllZ.get(i));
            }
        }

        if (! rawSfovStacksNeedingAlign3DStack.isEmpty()) {

            final int parallelism = Math.min(MFOVAsTileClient.MAX_PARTITIONS_FOR_ONE_WEB_SERVER, rawSfovStacksNeedingAlign3DStack.size());

            LOG.info("buildAlign3DSfovStacks: distributing build of {} stack(s) with parallelism {} (defaultParallelism={})",
                     rawSfovStacksNeedingAlign3DStack.size(), parallelism, sparkContext.defaultParallelism());

            final JavaRDD<StackWithZValues> rddAlignedStacks = sparkContext.parallelize(rawSfovStacksNeedingAlign3DStack,
                                                                                        parallelism);

            final Function<StackWithZValues, StackId> buildAlign3DStackFunction = stackWithAllZ -> {

                LogUtilities.setupExecutorLog4j(stackWithAllZ.getStackId().toDevString());

                final StackId rawSfovStackId = stackWithAllZ.getStackId();
                final StackId renderedLayerStackId = rawSfovStackId.withStackSuffix(renderedLayerStackSuffix);
                final StackId alignedLayerStackId = rawSfovStackId.withStackSuffix(alignedLayerStackSuffixForRaw);
                final StackId align3DSfovStackId = rawSfovStackId.withStackSuffix(align3DSfovStackSuffix);
                final String align3DSfovStack = align3DSfovStackId.getStack();

                final RenderDataClient workerDataClient = new RenderDataClient(baseDataUrl,
                                                                               rawSfovStackId.getOwner(),
                                                                               rawSfovStackId.getProject());

                final StackMetaData rawSfovStackMetaData = workerDataClient.getStackMetaData(rawSfovStackId.getStack());
                workerDataClient.setupDerivedStack(rawSfovStackMetaData, align3DSfovStack);

                for (final Double z : stackWithAllZ.getzValues()) {
                    final ResolvedTileSpecCollection align3DTiles = buildAlign3DTileSpecsForZ(workerDataClient,
                                                                                              rawSfovStackId.getStack(),
                                                                                              z,
                                                                                              renderedLayerStackId.getStack(),
                                                                                              alignedLayerStackId.getStack(),
                                                                                              layerAsTile.getLayerRenderScale());
                    workerDataClient.saveResolvedTiles(align3DTiles, align3DSfovStack, z);
                }

                workerDataClient.setStackState(align3DSfovStack, StackMetaData.StackState.COMPLETE);

                return align3DSfovStackId;
            };

            final JavaRDD<StackId> rddBuiltStacks = rddAlignedStacks.map(buildAlign3DStackFunction);
            final List<StackId> builtStacks = rddBuiltStacks.collect();

            LOG.info("buildAlign3DSfovStacks: completed build of {} stack(s)", builtStacks.size());
        }

        LOG.info("buildAlign3DSfovStacks: exit");
    }

    private static TileSpec getLayerTileSpec(final String stack,
                                             final ResolvedTileSpecCollection resolveTiles) throws IOException {
        final Collection<TileSpec> tileSpecList = resolveTiles.getTileSpecs();
        if (tileSpecList.size() != 1) {
            throw new IOException("expected 1 tile in " + stack + " but found " + tileSpecList.size());
        }
        return tileSpecList.iterator().next();
    }

    @Nonnull
    private static ResolvedTileSpecCollection buildAlign3DTileSpecsForZ(final RenderDataClient dataClient,
                                                                        final String rawSfovStack,
                                                                        final double z,
                                                                        final String renderedLayerStack,
                                                                        final String alignedLayerStack,
                                                                        final double layerAsTileRenderScale)
            throws IOException {

        final String stackZContext = rawSfovStack + " z " + z;

        // example SFOV tile spec:
        // {
        //   "tileId": "w61_magc0145_scan004_m0009_r32_s49",
        //   ...
        //   "transforms": {
        //     "type": "list",
        //     "specList": [
        //       {
        //         "className": "org.janelia.alignment.transform.ExponentialFunctionOffsetTransform",
        //         "dataString": "3.164065083689898,0.010223592506552219,0.0,0"
        //       },
        //       {
        //         "className": "mpicbg.trakem2.transform.AffineModel2D",
        //         "dataString": "0.9989275629591378 -0.0034777133301720285 0.001945630942943617 1.0041687686777434 33733.93229071569 52312.024345042184"
        //       }
        //     ]
        //   },
        //   ...
        // }
        final ResolvedTileSpecCollection sfovTiles = dataClient.getResolvedTiles(rawSfovStack, z);

        // example layer-as-tile spec:
        // {
        //   "tileId": "w61_s109_r00_gc_par_crc_aso_z001",
        //   ...
        //   "transforms": {
        //     "type": "list",
        //     "specList": [
        //       {
        //         "className": "mpicbg.trakem2.transform.AffineModel2D",
        //         "dataString": "0.9995111984001582 3.8059309390139125E-4 -2.3150605242131648E-4 1.0058163225403765 1.7289345344867812 13.165940488916831"
        //       }
        //     ]
        //   },
        //   ...
        // }

        final ResolvedTileSpecCollection renderedLayerTiles = dataClient.getResolvedTiles(renderedLayerStack, z);
        final TileSpec renderedLayerTileSpec = getLayerTileSpec(renderedLayerStack, renderedLayerTiles);
        final TransformSpec renderedLayerTransformSpec = renderedLayerTileSpec.getLastTransform();
        final AffineModel2D renderedLayerModel =
                ResolvedTileSpecCollection.getAffineModelForSpec(stackZContext,
                                                                 renderedLayerTransformSpec);

        // Invert renderedLayerModel to convert the scaled SFOV (layer world) coordinate
        // into a local coordinate before the alignment is applied.
        // This accounts for each rendered layer image having a different size and world origin.
        final AffineModel2D layerToRenderedLocal = renderedLayerModel.createInverse();

        final ResolvedTileSpecCollection alignedLayerTiles = dataClient.getResolvedTiles(alignedLayerStack, z);
        final TileSpec alignedLayerTileSpec = getLayerTileSpec(alignedLayerStack, alignedLayerTiles);
        final TransformSpec alignedLayerTransformSpec = alignedLayerTileSpec.getLastTransform();
        final AffineModel2D alignedLayerModel =
                ResolvedTileSpecCollection.getAffineModelForSpec(stackZContext,
                                                                 alignedLayerTransformSpec);

        final AffineModel2D scaleSFOVToLayer = new AffineModel2D();
        scaleSFOVToLayer.set(layerAsTileRenderScale, 0, 0,
                             layerAsTileRenderScale, 0, 0);

        final AffineModel2D scaleLayerToSFOV = new AffineModel2D();
        final double inverseLayerAsTileRenderScale = 1.0 / layerAsTileRenderScale;
        scaleLayerToSFOV.set(inverseLayerAsTileRenderScale, 0, 0,
                             inverseLayerAsTileRenderScale, 0, 0);

        final AffineModel2D sfovModel = new AffineModel2D();
        sfovModel.set(alignedLayerModel);
        sfovModel.concatenate(layerToRenderedLocal); // alignedLayerModel * renderedLayerModel^-1
        sfovModel.concatenate(scaleSFOVToLayer);     // alignedLayerModel * renderedLayerModel^-1 * scaleSFOVToLayer
        sfovModel.preConcatenate(scaleLayerToSFOV);  // scaleLayerToSFOV * alignedLayerModel * renderedLayerModel^-1 * scaleSFOVToLayer

        final String sfovModelDataString = sfovModel.toDataString();

        final LeafTransformSpec zLayerTransformSpec =
                new LeafTransformSpec("mpicbg.trakem2.transform.AffineModel2D",
                                      sfovModelDataString);

        LOG.info("buildAlign3DTileSpecsForZ: adding AffineModel2D transform {} to all tiles in {}",
                 sfovModelDataString, stackZContext);

        for (final TileSpec tileSpec : sfovTiles.getTileSpecs()) {
            sfovTiles.addTransformSpecToTile(tileSpec.getTileId(),
                                             zLayerTransformSpec,
                                             TransformApplicationMethod.PRE_CONCATENATE_LAST);
        }

        return sfovTiles;
    }

    // Serializable information that can be used to build RenderTilesClient instances in remote Spark workers
    public static class JavaRenderTilesClientInfoForLayer
            implements Serializable {

        private final String baseDataUrl;
        private final StackId stackId;
        private final double z;
        private final TileRenderParameters tileRender;

        public JavaRenderTilesClientInfoForLayer(final String baseDataUrl,
                                                 final StackId stackId,
                                                 final double z,
                                                 final LayerAsTileParameters layerAsTile,
                                                 final String runTimestamp) {
            this.baseDataUrl = baseDataUrl;
            this.stackId = stackId;
            this.z = z;
            final String hackStack = stackId.getStack() + layerAsTile.getRenderedLayerStackSuffix();
            this.tileRender = TileRenderParameters.buildXAsTileVersion(layerAsTile.getLayerRootDirectory(),
                                                                       runTimestamp,
                                                                       hackStack);
        }

        public org.janelia.render.client.tile.RenderTilesClient buildJavaRenderTilesClient() {
            return new org.janelia.render.client.tile.RenderTilesClient(
                    new RenderDataClient(baseDataUrl, stackId.getOwner(), stackId.getProject()),
                    stackId.getStack(),
                    tileRender);
        }

        public void setupHackStackAndStorage()
                throws IOException {
            final org.janelia.render.client.tile.RenderTilesClient jClient = buildJavaRenderTilesClient();
            jClient.setupHackStackAsNeeded();
            jClient.setupStorageDirectories();
        }

        public int renderTiles()
                throws IOException {
            LogUtilities.setupExecutorLog4j(stackId.toDevString());
            LOG.info("renderTiles: entry, stackId={}, z={}", stackId.toDevString(), z);
            final org.janelia.render.client.tile.RenderTilesClient jClient = buildJavaRenderTilesClient();
            jClient.renderTiles(Collections.singletonList(z));
            return 1;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(LayerAsTileClient.class);
}
