package org.janelia.render.client;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

/**
 * Java client for generating SIFT point matches for one or more canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class PointMatchClient {

    public enum CanvasGroupIdAlgorithm {

        /** Assign canvas group id based upon the sectionId value of the first rendered tile. */
        FIRST_TILE_SECTION_ID,

        /** Assign canvas group id based upon the z value of the first rendered tile. */
        FIRST_TILE_Z,

        /** Assign canvas group id based upon the match collection name. */
        COLLECTION
    }

    public enum CanvasIdAlgorithm {

        /** Assign canvas id based upon the id of the first rendered tile. */
        FIRST_TILE_ID,

        /** Assign canvas id based upon the z value of the first rendered tile. */
        FIRST_TILE_Z,

        /** Assign canvas id based upon the derived canvas name (e.g. c_00001). */
        CANVAS_NAME
    }

    public enum RenderFileFormat {
        JPG,
        PNG,
        TIF
    }

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @ParametersDelegate
        public MatchDerivationParameters match = new MatchDerivationParameters();

        @ParametersDelegate
        FeatureExtractionParameters featureExtraction = new FeatureExtractionParameters();

        @ParametersDelegate
        FeatureRenderClipParameters clip = new FeatureRenderClipParameters();

        @Parameter(
                names = "--firstCanvasPosition",
                description = "When clipping, identifies the relative position of the first canvas to the second canvas"
        )
        public MontageRelativePosition firstCanvasPosition;

        @Parameter(
                names = "--renderScale",
                description = "Render canvases at this scale"
        )
        public Double renderScale = 1.0;

        @Parameter(names = "--fillWithNoise",
                description = "Fill each canvas image with noise before rendering to improve point match derivation",
                arity = 1)
        public boolean fillWithNoise = true;

        @Parameter(
                names = "--renderFileFormat",
                description = "Format for saved canvases (only relevant if debugDirectory is specified)"
        )
        public RenderFileFormat renderFileFormat = RenderFileFormat.JPG;

        @Parameter(
                names = "--numberOfThreads",
                description = "Number of threads to use for processing"
        )
        public int numberOfThreads = 1;

        @Parameter(
                names = "--matchStorageFile",
                description = "File to store matches (omit if matches should be stored through web service)"
        )
        public String matchStorageFile = null;

        @Parameter(
                names = "--canvasGroupIdAlgorithm",
                description = "Algorithm for deriving canvas group ids"
        )
        public CanvasGroupIdAlgorithm canvasGroupIdAlgorithm = CanvasGroupIdAlgorithm.FIRST_TILE_SECTION_ID;

        @Parameter(
                names = "--canvasIdAlgorithm",
                description = "Algorithm for deriving canvas ids"
        )
        public CanvasIdAlgorithm canvasIdAlgorithm = CanvasIdAlgorithm.FIRST_TILE_ID;

        @Parameter(
                names = "--debugDirectory",
                description = "Directory to save rendered canvases for debugging (omit to keep rendered data in memory only)"
        )
        public String debugDirectory = null;
        File validatedDebugDirectory = null;

        @Parameter(
                description = "canvas_1_URL canvas_2_URL [canvas_p_URL canvas_q_URL] ... (each URL pair identifies render parameters for canvas pairs)",
                required = true)
        public List<String> renderParameterUrls;

        /**
         * @param  matchId  derived match id for canvas.
         *
         * @return file for debug save of rendered canvas.
         */
        File getCanvasFile(final String matchId) {
            File canvasFile = null;
            if (validatedDebugDirectory != null) {
                canvasFile = new File(validatedDebugDirectory,
                                      matchId + "." + renderFileFormat.toString().toLowerCase());
            }
            return canvasFile;
        }

        /**
         * @param  renderParameters  render parameters used to generate the current canvas.
         *
         * @return match group id derived using the {@link #canvasGroupIdAlgorithm}.
         */
        String getCanvasGroupId(final RenderParameters renderParameters) {
            String matchGroupId = null;
            if (renderParameters.hasTileSpecs()) {
                switch (canvasGroupIdAlgorithm) {
                    case FIRST_TILE_SECTION_ID:
                        matchGroupId = getTileSectionId(renderParameters.getTileSpecs().get(0), matchClient.collection);
                        break;
                    case FIRST_TILE_Z:
                        matchGroupId = getTileZId(renderParameters.getTileSpecs().get(0), matchClient.collection);
                        break;
                }
            }
            if (matchGroupId == null) {
                matchGroupId = matchClient.collection;
            }
            return matchGroupId;
        }

        /**
         * @param  renderParameters  render parameters used to generate the current canvas.
         * @param  canvasName        index based name for the current canvas (e.g. c_00001).
         *
         * @return match id derived using the {@link #canvasIdAlgorithm}.
         */
        String getCanvasId(final RenderParameters renderParameters,
                           final String canvasName) {
            String matchId = null;
            if (renderParameters.hasTileSpecs()) {
                switch (canvasIdAlgorithm) {
                    case FIRST_TILE_ID:
                        matchId = renderParameters.getTileSpecs().get(0).getTileId();
                        break;
                    case FIRST_TILE_Z:
                        matchId = getTileZId(renderParameters.getTileSpecs().get(0), canvasName);
                        break;
                }
            }
            if (matchId == null) {
                matchId = canvasName;
            }
            return matchId;
        }

        private String getTileSectionId(final TileSpec tileSpec,
                                  final String defaultValue) {
            String sectionId = defaultValue;
            final LayoutData layout = tileSpec.getLayout();
            if (layout != null) {
                sectionId = layout.getSectionId();
            }
            return sectionId;
        }

        private String getTileZId(final TileSpec tileSpec,
                                  final String defaultValue) {
            String zId = defaultValue;
            final Double z = tileSpec.getZ();
            if (z != null) {
                zId = String.valueOf(z);
                if (zId.indexOf('.') == -1) {
                    zId = zId + ".0";
                }
            }
            return zId;
        }

    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final PointMatchClient client = new PointMatchClient(parameters);

                client.extractFeatures();

                final List<CanvasMatches> canvasMatchesList = client.deriveMatches();

                client.saveMatches(canvasMatchesList);
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final Map<String, CanvasData> canvasUrlToDataMap;
    private final RenderDataClient renderDataClient;

    PointMatchClient(final Parameters clientParameters)
            throws IllegalArgumentException {

        if ((clientParameters.renderParameterUrls.size() % 2) != 0) {
            throw new IllegalArgumentException("odd number of canvas URLs specified, URLs must be paired");
        }

        this.parameters = clientParameters;

        if (clientParameters.debugDirectory != null) {
            try {
                clientParameters.validatedDebugDirectory = new File(clientParameters.debugDirectory).getCanonicalFile();
                if (! clientParameters.validatedDebugDirectory.exists()) {
                    if (! clientParameters.validatedDebugDirectory.mkdirs()) {
                        throw new IllegalArgumentException(
                                "failed to create debugDirectory " + clientParameters.validatedDebugDirectory);
                    }
                }
            } catch (final IOException e) {
                throw new IllegalArgumentException(
                        "invalid debugDirectory '" + clientParameters.debugDirectory + "' specified", e);
            }
        }

        if (parameters.clip.hasValue()) {

            if (clientParameters.renderParameterUrls.size() != 2) {
                throw new IllegalArgumentException("clipping is only supported for single pair runs");
            }

            if (parameters.firstCanvasPosition == null) {
                throw new IllegalArgumentException("--firstCanvasPosition must be specified for clipping");
            }

        }

        this.canvasUrlToDataMap = new LinkedHashMap<>(clientParameters.renderParameterUrls.size() * 2);

        for (final String canvasUrlString : clientParameters.renderParameterUrls) {
            if (! this.canvasUrlToDataMap.containsKey(canvasUrlString)) {
                this.canvasUrlToDataMap.put(canvasUrlString, new CanvasData(canvasUrlString,
                                                                            parameters.renderScale,
                                                                            canvasUrlToDataMap.size(),
                                                                            clientParameters));
            }
        }

        this.renderDataClient = new RenderDataClient(clientParameters.matchClient.baseDataUrl,
                                                     clientParameters.matchClient.owner,
                                                     clientParameters.matchClient.collection);
    }

     /**
     * Extract features from distinct set of canvases.
     */
    void extractFeatures() throws Exception {

        LOG.info("extractFeatures: entry, extracting from {} canvases", canvasUrlToDataMap.size());

        final List<CanvasFeatureExtractorThread> extractorList = new ArrayList<>(canvasUrlToDataMap.size());

        for (final String canvasUrl : canvasUrlToDataMap.keySet()) {
            extractorList.add(new CanvasFeatureExtractorThread(canvasUrlToDataMap.get(canvasUrl),
                                                               parameters));
        }

        if (parameters.numberOfThreads > 1) {

            for (final CanvasFeatureExtractorThread extractorThread : extractorList) {
                extractorThread.start();
            }

            for (final CanvasFeatureExtractorThread extractorThread : extractorList) {
                LOG.info("extractFeatures: waiting for {} to finish ...", extractorThread);
                extractorThread.join();
            }

        } else {

            for (final CanvasFeatureExtractorThread extractorThread : extractorList) {
                //noinspection CallToThreadRun
                extractorThread.run();
            }

        }

        LOG.info("extractFeatures: exit");
    }

    /**
     * Derive point matches for each canvas pair and write results.
     */
    List<CanvasMatches> deriveMatches() throws Exception {

        LOG.info("deriveMatches: entry, extracting from {} canvases", canvasUrlToDataMap.size());

        final List<CanvasFeatureMatcherThread> matcherList = new ArrayList<>(parameters.renderParameterUrls.size());

        final CanvasFeatureMatcher matcher = new CanvasFeatureMatcher(parameters.match,
                                                                      parameters.renderScale);

        String pUrlString;
        String qUrlString;
        for (int i = 1; i < parameters.renderParameterUrls.size(); i = i + 2) {
            pUrlString = parameters.renderParameterUrls.get(i - 1);
            qUrlString = parameters.renderParameterUrls.get(i);
            matcherList.add(new CanvasFeatureMatcherThread(canvasUrlToDataMap.get(pUrlString),
                                                           canvasUrlToDataMap.get(qUrlString),
                                                           matcher));
        }


        if (parameters.numberOfThreads > 1) {

            for (final CanvasFeatureMatcherThread matcherThread : matcherList) {
                matcherThread.start();
            }

            for (final CanvasFeatureMatcherThread matcherThread : matcherList) {
                LOG.info("extractFeatures: waiting for {} to finish ...", matcherThread);
                matcherThread.join();
            }

        } else {

            for (final CanvasFeatureMatcherThread matcherThread : matcherList) {
                //noinspection CallToThreadRun
                matcherThread.run();
            }

        }

        final List<CanvasMatches> canvasMatchesList = new ArrayList<>(matcherList.size());
        for (final CanvasFeatureMatcherThread matcherThread : matcherList) {
            matcherThread.addMatchesToList(canvasMatchesList);
        }

        LOG.info("deriveMatches: exit");

        return canvasMatchesList;
    }

    private void saveMatches(final List<CanvasMatches> canvasMatchesList) throws Exception {

        LOG.info("saveMatches: entry, canvasMatchesList.size={}", canvasMatchesList.size());

        final List<CanvasMatches> filteredCanvasMatchesList = new ArrayList<>(canvasMatchesList.size());
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            if (canvasMatches.size() > 0) {
                filteredCanvasMatchesList.add(canvasMatches);
            }
        }

        final int numberOfPairsWithoutMatches = canvasMatchesList.size() - filteredCanvasMatchesList.size();

        if (numberOfPairsWithoutMatches > 0) {
            LOG.info("saveMatches: filtered out {} pairs with no matches", numberOfPairsWithoutMatches);
        }

        if (filteredCanvasMatchesList.size() > 0) {

            CanvasMatches canvasMatches;
            if (parameters.matchStorageFile != null) {

                final Path storagePath = Paths.get(parameters.matchStorageFile);

                try (final BufferedWriter writer = Files.newBufferedWriter(storagePath, StandardCharsets.UTF_8)) {

                    writer.write("[\n");

                    for (int i = 0; i < filteredCanvasMatchesList.size(); i++) {
                        if (i > 0) {
                            writer.write(",\n");
                        }
                        canvasMatches = filteredCanvasMatchesList.get(i);
                        writer.write(canvasMatches.toJson());
                    }

                    writer.write("\n]\n");
                }


            } else {

                renderDataClient.saveMatches(filteredCanvasMatchesList);

            }

        } else {
            LOG.info("saveMatches: no pairs have matches so there is nothing to save");
        }

        LOG.info("saveMatches: exit");
    }

    /**
     * Helper class to hold data (render parameters, features, etc.) for each canvas.
     */
    public static class CanvasData {

        private final RenderParameters renderParameters;
        private final double renderScale;
        private final CanvasId canvasId;
        private List<Feature> featureList;

        CanvasData(final String canvasUrl,
                   final double renderScale,
                   final int canvasIndex,
                   final Parameters clientParameters) {

            final MontageRelativePosition relativePosition;
            if (clientParameters.clip.hasValue()) {
                relativePosition = canvasIndex == 0 ?
                                   clientParameters.firstCanvasPosition :
                                   clientParameters.firstCanvasPosition.getOpposite();
            } else {
                relativePosition = null;
            }

            final CanvasId loaderCanvasId = new CanvasId("canvas",
                                                         String.valueOf(canvasIndex),
                                                         relativePosition);

            final CanvasIdWithRenderContext canvasIdWithRenderContext =
                    new CanvasIdWithRenderContext(loaderCanvasId,
                                                  "feature_0",
                                                  canvasUrl,
                                                  clientParameters.clip.clipWidth,
                                                  clientParameters.clip.clipHeight);

            this.renderParameters = canvasIdWithRenderContext.loadRenderParameters();
            this.renderParameters.setScale(renderScale);
            this.renderScale = renderScale;

            final String groupId = clientParameters.getCanvasGroupId(this.renderParameters);
            final String canvasName = "c_" + String.format("%05d", canvasIndex);
            final String id = clientParameters.getCanvasId(this.renderParameters, canvasName);
            this.canvasId = new CanvasId(groupId, id, relativePosition);
            this.canvasId.setClipOffsets(canvasIdWithRenderContext.getClipOffsets());
            this.featureList = null;
        }

        void setFeatureList(final List<Feature> featureList) {
            this.featureList = featureList;
        }

        @Override
        public String toString() {
            return canvasId.getGroupId() + "__" + canvasId.getId();
        }
    }

    /**
     * Thread wrapper that allows feature extraction to be done in parallel.
     */
    private static class CanvasFeatureExtractorThread extends Thread {

        private final CanvasData canvasData;
        private final File renderFile;
        private final CanvasFeatureExtractor extractor;

        CanvasFeatureExtractorThread(final CanvasData canvasData,
                                     final Parameters clientParameters) {

            this.canvasData = canvasData;
            this.renderFile = clientParameters.getCanvasFile(canvasData.canvasId.getId());

            final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
            siftParameters.fdSize = clientParameters.featureExtraction.fdSize;
            siftParameters.steps = clientParameters.featureExtraction.steps;

            this.extractor = new CanvasFeatureExtractor(siftParameters,
                                                        clientParameters.featureExtraction.minScale,
                                                        clientParameters.featureExtraction.maxScale);
        }

        @Override
        public void run() {
            canvasData.setFeatureList(
                    extractor.extractFeatures(canvasData.renderParameters,
                                              renderFile));
        }

        @Override
        public String toString() {
            return "CanvasFeatureExtractorThread{" + canvasData.canvasId + '}';
        }
    }

    /**
     * Thread wrapper that allows match derivation to be done in parallel.
     */
    private static class CanvasFeatureMatcherThread extends Thread {

        private final CanvasData pCanvasData;
        private final CanvasData qCanvasData;

        private final CanvasFeatureMatcher matcher;

        private CanvasMatchResult matchResult;

        CanvasFeatureMatcherThread(final CanvasData pCanvasData,
                                   final CanvasData qCanvasData,
                                   final CanvasFeatureMatcher matcher) {
            this.pCanvasData = pCanvasData;
            this.qCanvasData = qCanvasData;
            this.matcher = matcher;
        }

        @Override
        public void run() {
            matchResult = matcher.deriveMatchResult(pCanvasData.featureList, qCanvasData.featureList);
        }

        void addMatchesToList(final List<CanvasMatches> targetList) {
            matchResult.addInlierMatchesToList(pCanvasData.canvasId.getGroupId(),
                                               pCanvasData.canvasId.getId(),
                                               qCanvasData.canvasId.getGroupId(),
                                               qCanvasData.canvasId.getId(),
                                               pCanvasData.renderScale,
                                               pCanvasData.canvasId.getClipOffsets(),
                                               qCanvasData.canvasId.getClipOffsets(),
                                               targetList);
        }

        @Override
        public String toString() {
            return "CanvasFeatureMatcherThread{" + pCanvasData.canvasId + "__" + qCanvasData.canvasId + '}';
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(PointMatchClient.class);
}
