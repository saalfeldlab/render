package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureList;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.render.client.parameter.FeatureRenderParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.render.client.cache.CachedCanvasFeatures;
import org.janelia.render.client.cache.CanvasFeatureListLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for extracting and storing SIFT features for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class FeatureClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @Parameter(
                names = "--baseDataUrl",
                description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
                required = true)
        public String baseDataUrl;

        @ParametersDelegate
        FeatureRenderParameters featureRender = new FeatureRenderParameters();

        @ParametersDelegate
        FeatureRenderClipParameters featureRenderClip = new FeatureRenderClipParameters();

        @ParametersDelegate
        FeatureExtractionParameters featureExtraction = new FeatureExtractionParameters();

        @Parameter(
                names = "--rootFeatureDirectory",
                description = "Root directory for saved feature lists (features saved to [root]/[canvas_group_id]/[canvas_id].features.json.gz)",
                required = true)
        public String rootFeatureDirectory;

        @Parameter(
                names = "--pairJson",
                description = "JSON file where tile pairs are stored (.json, .gz, or .zip)",
                required = true,
                order = 5)
        public List<String> pairJson;

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final FeatureClient client = new FeatureClient(parameters);
                final SparkConf conf = new SparkConf().setAppName(FeatureClient.class.getSimpleName());
                client.run(conf);

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    FeatureClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
    }

    public void run(final SparkConf conf) throws IOException, URISyntaxException {

        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        for (final String pairJsonFileName : parameters.pairJson) {
            generateFeatureListsForPairFile(sparkContext, pairJsonFileName);
        }

        sparkContext.stop();
    }

    private void generateFeatureListsForPairFile(final JavaSparkContext sparkContext,
                                                 final String pairJsonFileName)
            throws IOException, URISyntaxException {

        LOG.info("generateFeatureListsForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);
        final String renderParametersUrlTemplate =
                renderableCanvasIdPairs.getRenderParametersUrlTemplate(parameters.baseDataUrl);
        final Set<CanvasId> canvasIdSet = new HashSet<>(renderableCanvasIdPairs.size());
        for (final OrderedCanvasIdPair pair : renderableCanvasIdPairs.getNeighborPairs()) {
            canvasIdSet.add(pair.getP());
            canvasIdSet.add(pair.getQ());
        }

        LOG.info("generateFeatureListsForPairFile: found {} distinct canvasIds", canvasIdSet.size());

        generateFeatureListsForCanvases(sparkContext,
                                        renderParametersUrlTemplate,
                                        new ArrayList<>(canvasIdSet),
                                        parameters.featureRender,
                                        parameters.featureRenderClip,
                                        parameters.featureExtraction,
                                        new File(parameters.rootFeatureDirectory).getAbsoluteFile());
    }

    private static void generateFeatureListsForCanvases(final JavaSparkContext sparkContext,
                                                        final String renderParametersUrlTemplate,
                                                        final List<CanvasId> canvasIdList,
                                                        final FeatureRenderParameters featureRenderParameters,
                                                        final FeatureRenderClipParameters featureRenderClipParameters,
                                                        final FeatureExtractionParameters featureExtractionParameters,
                                                        final File rootDirectory)
            throws URISyntaxException {

        final CanvasRenderParametersUrlTemplate urlTemplateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderParametersUrlTemplate,
                        featureRenderParameters.renderFullScaleWidth,
                        featureRenderParameters.renderFullScaleHeight,
                        featureRenderParameters.renderScale,
                        featureRenderParameters.renderWithFilter,
                        featureRenderParameters.renderFilterListName,
                        featureRenderParameters.renderWithoutMask);

        urlTemplateForRun.setClipInfo(featureRenderClipParameters.clipWidth, featureRenderClipParameters.clipHeight);

        final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
        siftParameters.fdSize = featureExtractionParameters.fdSize;
        siftParameters.steps = featureExtractionParameters.steps;

        final CanvasFeatureExtractor featureExtractor =
                new CanvasFeatureExtractor(siftParameters,
                                           featureExtractionParameters.minScale,
                                           featureExtractionParameters.maxScale,
                                           featureRenderParameters.fillWithNoise);

        final CanvasFeatureListLoader featureLoader = new CanvasFeatureListLoader(urlTemplateForRun,
                                                                                  featureExtractor);

        final double renderScale = featureRenderParameters.renderScale;

        // broadcast to all nodes
        final Broadcast<CanvasFeatureListLoader> broadcastFeatureLoader = sparkContext.broadcast(featureLoader);

        final JavaRDD<CanvasId> rddCanvasIds = sparkContext.parallelize(canvasIdList);

        final JavaRDD<Integer> rddCanvasCounts = rddCanvasIds.map(
                (Function<CanvasId, Integer>) canvasId -> {

                    LogUtilities.setupExecutorLog4j(canvasId.getGroupId());

                    final CanvasFeatureListLoader localFeatureLoader = broadcastFeatureLoader.getValue();
                    final CachedCanvasFeatures canvasFeatures = localFeatureLoader.load(canvasId);
                    final CanvasFeatureList canvasFeatureList =
                            new CanvasFeatureList(canvasId,
                                                  localFeatureLoader.getRenderParametersUrl(canvasId),
                                                  renderScale,
                                                  localFeatureLoader.getClipWidth(),
                                                  localFeatureLoader.getClipHeight(),
                                                  canvasFeatures.getFeatureList());
                    CanvasFeatureList.writeToStorage(rootDirectory, canvasFeatureList);
                    return 1;
                }
        );

        final List<Integer> canvasCountList = rddCanvasCounts.collect();

        LOG.info("generateFeatureListsForCanvases: collected stats");

        long totalSaved = 0;
        for (final Integer canvasCount : canvasCountList) {
            totalSaved += canvasCount;
        }

        LOG.info("generateFeatureListsForCanvases: saved features for {} canvases", totalSaved);
    }


    private static final Logger LOG = LoggerFactory.getLogger(FeatureClient.class);
}
