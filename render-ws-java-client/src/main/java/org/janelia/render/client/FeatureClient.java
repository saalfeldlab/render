package org.janelia.render.client;

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

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureList;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.render.client.parameter.FeatureRenderParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for extracting and storing SIFT features for a specified set of canvas (e.g. tile) pairs.
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

        @Parameter(
                names = "--beginIndex",
                description = "Index of first pair to process"
        )
        public Integer beginIndex = 0;

        @Parameter(
                names = "--endIndex",
                description = "Index (inclusive) of last pair to process (or null to process all remaining)"
        )
        public Integer endIndex;

        int getExclusiveEndIndex(final int totalNumberOfPairs) {
            int exclusiveEndIndex = totalNumberOfPairs;
            if ((endIndex != null && endIndex < totalNumberOfPairs)) {
                exclusiveEndIndex = endIndex + 1;
            }
            return exclusiveEndIndex;
        }
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final FeatureClient client = new FeatureClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    FeatureClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
    }

    public void run() throws IOException, URISyntaxException {
        for (final String pairJsonFileName : parameters.pairJson) {
            generateFeatureListsForPairFile(pairJsonFileName);
        }
    }

    private void generateFeatureListsForPairFile(final String pairJsonFileName)
            throws IOException, URISyntaxException {

        LOG.info("generateFeatureListsForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);
        final String renderParametersUrlTemplate =
                renderableCanvasIdPairs.getRenderParametersUrlTemplate(parameters.baseDataUrl);
        final Set<CanvasId> canvasIdSet = new HashSet<>(renderableCanvasIdPairs.size());

        final List<OrderedCanvasIdPair> neighborPairs = renderableCanvasIdPairs.getNeighborPairs();
        final int exclusiveEndIndex = parameters.getExclusiveEndIndex(neighborPairs.size());

        OrderedCanvasIdPair pair;
        for (int i = parameters.beginIndex; i < exclusiveEndIndex; i++) {
            pair = neighborPairs.get(i);
            canvasIdSet.add(pair.getP());
            canvasIdSet.add(pair.getQ());
        }

        LOG.info("generateFeatureListsForPairFile: found {} distinct canvasIds for pairs ({}:{}]",
                 canvasIdSet.size(), parameters.beginIndex, exclusiveEndIndex);

        generateFeatureListsForCanvases(renderParametersUrlTemplate,
                                        new ArrayList<>(canvasIdSet),
                                        parameters.featureRender,
                                        parameters.featureRenderClip,
                                        parameters.featureExtraction,
                                        new File(parameters.rootFeatureDirectory).getAbsoluteFile());
    }

    private static void generateFeatureListsForCanvases(final String renderParametersUrlTemplate,
                                                        final List<CanvasId> canvasIdList,
                                                        final FeatureRenderParameters featureRenderParameters,
                                                        final FeatureRenderClipParameters featureRenderClipParameters,
                                                        final FeatureExtractionParameters featureExtractionParameters,
                                                        final File rootDirectory)
            throws IOException, URISyntaxException {

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

        final double renderScale = featureRenderParameters.renderScale;

        for (final CanvasId canvasId : canvasIdList) {

            final String renderParametersUrl = urlTemplateForRun.getRenderParametersUrl(canvasId);
            final RenderParameters renderParameters = urlTemplateForRun.getRenderParameters(canvasId,
                                                                                            renderParametersUrl);
            final double[] offsets = canvasId.getClipOffsets(); // HACK WARNING: offsets get applied by getRenderParameters call

            LOG.info("generateFeatureListsForCanvases: extracting features for {} with offsets ({}, {})",
                     canvasId, offsets[0], offsets[1]);

            final List<Feature> featureList = featureExtractor.extractFeatures(renderParameters, null);

            final CanvasFeatureList canvasFeatureList =
                    new CanvasFeatureList(canvasId,
                                          renderParametersUrl,
                                          renderScale,
                                          urlTemplateForRun.getClipWidth(),
                                          urlTemplateForRun.getClipHeight(),
                                          featureList);

            CanvasFeatureList.writeToStorage(rootDirectory, canvasFeatureList);
        }


        LOG.info("generateFeatureListsForCanvases: saved features for {} canvases", canvasIdList.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(FeatureClient.class);
}
