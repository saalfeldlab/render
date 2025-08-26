package org.janelia.render.client.spark.multisem;

import ij.process.ImageProcessor;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.imagefeatures.Feature;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TranslationModel1D;
import mpicbg.models.TranslationModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializable task for aligning SFOVs within a single MFOV.
 * This task handles fetching tile specs, deriving matches, optimizing tiles, and saving results.
 */
public class MfovPrealignTask implements Serializable {

    private final String baseDataUrl;
    private final StackId rawSfovStackId;
    private final StackId prealignedStackId;
    private final LayerMFOV layerMfov;

    private static final FeatureExtractionParameters FEATURE_EXTRACTION_PARAMETERS = new FeatureExtractionParameters();
    private static final MatchDerivationParameters MATCH_DERIVATION_PARAMETERS = new MatchDerivationParameters();
    private static final int CLIP_SIZE = 150;

    public MfovPrealignTask(
            final String baseDataUrl,
            final StackId rawSfovStackId,
            final StackId prealignedStackId,
            final LayerMFOV layerMfov
    ) {
        this.baseDataUrl = baseDataUrl;
        this.rawSfovStackId = rawSfovStackId;
        this.prealignedStackId = prealignedStackId;
        this.layerMfov = layerMfov;


        FEATURE_EXTRACTION_PARAMETERS.fdSize = 4;
        FEATURE_EXTRACTION_PARAMETERS.maxScale = 1.0;
        FEATURE_EXTRACTION_PARAMETERS.minScale = 0.25;
        FEATURE_EXTRACTION_PARAMETERS.steps = 5;

        MATCH_DERIVATION_PARAMETERS.matchFilter = MatchFilter.FilterType.SINGLE_SET;
        MATCH_DERIVATION_PARAMETERS.matchFullScaleCoverageRadius = 300.0;
        MATCH_DERIVATION_PARAMETERS.matchIterations = 1000;
        MATCH_DERIVATION_PARAMETERS.matchMaxEpsilonFullScale = 5.0f;
        MATCH_DERIVATION_PARAMETERS.matchMaxTrust = 4.0;
        MATCH_DERIVATION_PARAMETERS.matchMinCoveragePercentage = 0.0;
        MATCH_DERIVATION_PARAMETERS.matchMinInlierRatio = 0.0f;
        MATCH_DERIVATION_PARAMETERS.matchMinNumInliers = 10;
        MATCH_DERIVATION_PARAMETERS.matchModelType = ModelType.TRANSLATION;
        MATCH_DERIVATION_PARAMETERS.matchRod = 0.92f;
    }

    /**
     * Align and intensity correct all SFOVs within this MFOV and save the aligned tile specs to the prealigned stack.
     * Both alignment and intensity correction are performed by simple translation models.
     *
     * @throws RuntimeException if alignment fails
     */
    public void run() {
        try {
            LogUtilities.setupExecutorLog4j(prealignedStackId.toDevString() + "_" + layerMfov);

            LOG.info("alignMfov: entry");

            final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                     rawSfovStackId.getOwner(),
                                                                     rawSfovStackId.getProject());

            // 1. Fetch tile specs for all SFOVs in this MFOV
            final ResolvedTileSpecCollection mfovTiles = fetchMfovTileSpecs(dataClient);

            if (mfovTiles.getTileCount() == 0) {
                LOG.warn("alignMfov: no tiles found");
            } else {
                LOG.info("alignMfov: fetched {} tiles", mfovTiles.getTileCount());
            }

            // 2. Generate tile pairs for matching
            final List<OrderedCanvasIdPair> tilePairs = generateTilePairs(mfovTiles);

            if (tilePairs.isEmpty()) {
                LOG.warn("alignTiles: no tile pairs generated");
                return;
            }
            LOG.info("alignTiles: generated {} tile pairs", tilePairs.size());

            // Initialize the image processor cache with ~1Gb size to hold all tiles in memory
            final ImageProcessorCache cache = new ImageProcessorCache(1_000_000_000L, false, false);

            // 3. Align tiles within this MFOV
            final ResolvedTileSpecCollection alignedTiles = alignTiles(mfovTiles, tilePairs, cache);

            // 4. Perform intensity correction
            final ResolvedTileSpecCollection alignedIcTiles = intensityCorrectTiles(alignedTiles, tilePairs, cache);

            // 5. Push the aligned tile specs to the prealigned stack
            final int savedTileCount = saveMfovTiles(dataClient, alignedIcTiles);

            LOG.info("alignMfov: exit, aligned and saved {} tiles", savedTileCount);

        } catch (final Exception e) {
            LOG.error("alignMfov: failed", e);
            throw new RuntimeException("Failed to align MFOV " + layerMfov, e);
        }
    }

    /**
     * Fetch tile specs for all SFOVs belonging to this MFOV at the specified z layer.
     */
    private ResolvedTileSpecCollection fetchMfovTileSpecs(final RenderDataClient dataClient) throws IOException {
        final String matchPattern = "_" + layerMfov.getSimpleMfovName() + "_"; // limit to tiles in this MFOV
        return dataClient.getResolvedTiles(rawSfovStackId.getStack(),
                                           layerMfov.getZ(),
                                           matchPattern);
    }

    /**
     * Generate tile pairs for matching based on spatial proximity within the MFOV.
     */
    private List<OrderedCanvasIdPair> generateTilePairs(final ResolvedTileSpecCollection mfovTiles) {
        final List<OrderedCanvasIdPair> pairs = new ArrayList<>();
        final List<TileSpec> tileList = new ArrayList<>(mfovTiles.getTileSpecs());

        // Generate intersecting tile pairs
        for (int i = 0; i < tileList.size(); i++) {
            final TileSpec ti = tileList.get(i);
            final Rectangle2D rectI = new Rectangle2D.Double(ti.getMinX(), ti.getMinY(), ti.getWidth(), ti.getHeight());

            for (int j = i + 1; j < tileList.size(); j++) {
                final TileSpec tj = tileList.get(j);
                final Rectangle2D rectJ = new Rectangle2D.Double(tj.getMinX(), tj.getMinY(), tj.getWidth(), tj.getHeight());

                if (rectI.intersects(rectJ)) {
                    final CanvasId canvasIdI = new CanvasId(ti.getGroupId(), ti.getTileId());
                    final CanvasId canvasIdJ = new CanvasId(tj.getGroupId(), tj.getTileId());
                    pairs.add(new OrderedCanvasIdPair(canvasIdI, canvasIdJ, 0.0));
                }
            }
        }

        return pairs;
    }

    /**
     * Perform SIFT feature matching on the tile pairs.
     */
    private ResolvedTileSpecCollection alignTiles(
            final ResolvedTileSpecCollection tiles,
            final List<OrderedCanvasIdPair> tilePairs,
            final ImageProcessorCache cache
    ) {
        // Extract features from all mfov tiles
        final CanvasFeatureExtractor featureExtractor = CanvasFeatureExtractor.build(FEATURE_EXTRACTION_PARAMETERS);
        final Map<String, List<Feature>> mfovFeatures = new HashMap<>(tiles.getTileCount());
        final Map<String, Tile<TranslationModel2D>> modelTiles = new HashMap<>();

        for (final TileSpec tileSpec : tiles.getTileSpecs()) {
            final List<Feature> tileFeatures = extractBoundaryFeatures(cache, tileSpec, featureExtractor);

            mfovFeatures.put(tileSpec.getTileId(), tileFeatures);
            modelTiles.put(tileSpec.getTileId(), new Tile<>(new TranslationModel2D()));
        }

        // Match features between tile pairs
        final CanvasFeatureMatcher featureMatcher = new CanvasFeatureMatcher(MATCH_DERIVATION_PARAMETERS, 1.0);

        for (final OrderedCanvasIdPair pair : tilePairs) {
            final String tileIdI = pair.getP().getId();
            final String tileIdJ = pair.getQ().getId();

            LOG.info("performSiftAlignment: matching features for canvas pair {} <-> {}", tileIdI, tileIdJ);
            final CanvasMatchResult matchResult = featureMatcher.deriveMatchResult(
                    mfovFeatures.get(tileIdI),
                    mfovFeatures.get(tileIdJ)
            );

            // Connect tiles for optimization...
            final Tile<TranslationModel2D> modelTileI = modelTiles.get(tileIdI);
            final Tile<TranslationModel2D> modelTileJ = modelTiles.get(tileIdJ);
            if (matchResult == null || matchResult.getTotalNumberOfInliers() == 0) {
                // ... with fake matches if no inliers found
                final PointMatch fakeMatch = new PointMatch(
                        new Point(new double[] {0.0, 0.0}), new Point(new double[] {0.0, 0.0}), 1e-4
                );
                modelTileI.connect(modelTileJ, Collections.singletonList(fakeMatch));
            } else {
                // ... with real matches if inliers found
                final List<PointMatch> pointMatches = matchResult.getInlierPointMatchList();
                modelTileI.connect(modelTileJ, pointMatches);
            }
        }

        // Optimize the tiles
        final TileConfiguration tc = new TileConfiguration();
        tc.addTiles(modelTiles.values());
        tc.fixTile(modelTiles.values().stream().findFirst().orElseThrow());

        try {
            final ErrorStatistic errorStatistic = new ErrorStatistic(1001);
            tc.optimizeSilently(errorStatistic, 0.5, 1000, 100);
        } catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
            throw new RuntimeException("Failed to optimize tile transformations", e);
        }


        // Add the transformations to the tiles
        final double[] translation = new double[6];
        for (final Map.Entry<String, Tile<TranslationModel2D>> entry : modelTiles.entrySet()) {
            final String tileId = entry.getKey();
            final TranslationModel2D model = entry.getValue().getModel();

            // Combine the translation from the model with the previous translation
            model.toArray(translation);
            final LeafTransformSpec previousTransformSpec = (LeafTransformSpec) tiles.getTileSpec(tileId).getLastTransform();

            final String[] previousCoefficients = previousTransformSpec.getDataString().split(" ");
            final double x = Double.parseDouble(previousCoefficients[4]) + translation[4];
            final double y = Double.parseDouble(previousCoefficients[5]) + translation[5];
            final LeafTransformSpec newTransformSpec =  new LeafTransformSpec(
                    "mpicbg.trakem2.transform.AffineModel2D",
                    "1 0 0 1 " + x + " " + y
            );

            // Set the transformation model to the tile spec
            tiles.addTransformSpecToTile(tileId, newTransformSpec, ResolvedTileSpecCollection.TransformApplicationMethod.REPLACE_LAST);
        }

        return tiles;
    }

    /**
     * Extract features from boundary regions.
     */
    private static List<Feature> extractBoundaryFeatures(
            final ImageProcessorCache cache,
            final TileSpec tileSpec,
            final CanvasFeatureExtractor featureExtractor
    ) {
        // Load an image processor for the given tile.
        final ImageProcessor ip = loadImageProcessor(cache, tileSpec);
        final int width = ip.getWidth();
        final int height = ip.getHeight();

        // Left, right, top, and bottom boundaries
        final List<Feature> features = new ArrayList<>(extractFeaturesFromRoi(featureExtractor, ip, 0, 0, CLIP_SIZE, height));
        features.addAll(extractFeaturesFromRoi(featureExtractor, ip, width - CLIP_SIZE, 0, CLIP_SIZE, height));
        features.addAll(extractFeaturesFromRoi(featureExtractor, ip, CLIP_SIZE, 0, width - 2 * CLIP_SIZE, CLIP_SIZE));
        features.addAll(extractFeaturesFromRoi(featureExtractor, ip, CLIP_SIZE, height - CLIP_SIZE, width - 2 * CLIP_SIZE, CLIP_SIZE));

        // Move the features to the global coordinate system
        for (final Feature feature : features) {
            feature.location[0] += tileSpec.getMinX();
            feature.location[1] += tileSpec.getMinY();
        }

        return features;
    }

    private static ImageProcessor loadImageProcessor(
            final ImageProcessorCache cache,
            final TileSpec tileSpec
    ) {
        final int downSampleLevel = 0;
        final boolean isMask = false;
        final boolean convertTo16Bit = false;
        final int slice = 0;
        final ChannelSpec firstChannel = tileSpec.getAllChannels().get(0);
        final ImageAndMask image = firstChannel.getMipmap(downSampleLevel);
        return cache.get(image.getImageUrl(), downSampleLevel, isMask, convertTo16Bit, image.getImageLoaderType(), slice);
    }

    /**
     * Extract features from ROI and shift them
     */
    private static List<Feature> extractFeaturesFromRoi(
            final CanvasFeatureExtractor featureExtractor,
            final ImageProcessor ip,
            final int xShift,
            final int yShift,
            final int w,
            final int h
    ) {
        // Extract the specified ROI from the image processor
        ip.setRoi(xShift, yShift, w, h);
        final ImageProcessor roiIp = ip.crop();

        // Extract features from the ROI and place them in the tile coordinate system
        final List<Feature> features = featureExtractor.extractFeaturesFromImageAndMask(roiIp, null);
        for (final Feature feature : features) {
            feature.location[0] += xShift;
            feature.location[1] += yShift;
        }
        return features;
    }

    /**
     * Perform intensity correction on the aligned tiles.
     */
    private ResolvedTileSpecCollection intensityCorrectTiles(
            final ResolvedTileSpecCollection tiles,
            final List<OrderedCanvasIdPair> tilePairs,
            final ImageProcessorCache cache
    ) {
        LOG.info("intensityCorrectTiles: entry, tile count={}", tiles.getTileCount());
        final Map<String, Tile<TranslationModel1D>> modelTiles = new HashMap<>();

        // Initialize models for each tile spec
        for (final TileSpec tileSpec : tiles.getTileSpecs()) {
            // Create a model tile for each tile spec
            final Tile<TranslationModel1D> modelTile = new Tile<>(new TranslationModel1D());
            modelTiles.put(tileSpec.getTileId(), modelTile);
        }

        // Compute intensity differences between tile pairs
        for (final OrderedCanvasIdPair pair : tilePairs) {
            // Get the tile specs for the pair
            final String tileIdI = pair.getP().getId();
            final String tileIdJ = pair.getQ().getId();
            final TileSpec tileSpecI = tiles.getTileSpec(tileIdI);
            final TileSpec tileSpecJ = tiles.getTileSpec(tileIdJ);

            // Update the bounding boxes, since we just moved the tiles
            tileSpecI.deriveBoundingBox(tileSpecI.getMeshCellSize(), true);
            tileSpecJ.deriveBoundingBox(tileSpecJ.getMeshCellSize(), true);

            // Compute means on the overlap
            final Rectangle2D rectI = new Rectangle2D.Double(tileSpecI.getMinX(), tileSpecI.getMinY(), tileSpecI.getWidth(), tileSpecI.getHeight());
            final Rectangle2D rectJ = new Rectangle2D.Double(tileSpecJ.getMinX(), tileSpecJ.getMinY(), tileSpecJ.getWidth(), tileSpecJ.getHeight());
            final Rectangle2D overlap = rectI.createIntersection(rectJ);

            final double meanI = computeMeanIntensity(tileSpecI, overlap, cache);
            final double meanJ = computeMeanIntensity(tileSpecJ, overlap, cache);

            // Add point match
            final PointMatch pointMatch = new PointMatch(
                    new Point(new double[] {meanI}),
                    new Point(new double[] {meanJ})
            );
            final Tile<TranslationModel1D> modelTileI = modelTiles.get(tileIdI);
            final Tile<TranslationModel1D> modelTileJ = modelTiles.get(tileIdJ);
            modelTileI.connect(modelTileJ, Collections.singletonList(pointMatch));
        }

        // Optimize the translation models for intensity correction
        final TileConfiguration tc = new TileConfiguration();
        tc.addTiles(modelTiles.values());
        tc.fixTile(modelTiles.values().stream().findFirst().orElseThrow());

        try {
            final ErrorStatistic errorStatistic = new ErrorStatistic(1001);
            tc.optimizeSilently(errorStatistic, 0.5, 1000, 100);
        } catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
            throw new RuntimeException("Failed to optimize intensity correction", e);
        }

        // Apply the intensity shift to the tiles
        final double[] translation = new double[2];
        for (final Map.Entry<String, Tile<TranslationModel1D>> entry : modelTiles.entrySet()) {
            final String tileId = entry.getKey();
            final TranslationModel1D model = entry.getValue().getModel();
            final TileSpec tileSpec = tiles.getTileSpec(tileId);

            model.toArray(translation);
            final FilterSpec filterSpec = new FilterSpec(
                    "org.janelia.alignment.filter.AffineIntensityFilter",
                    Map.of("a", String.valueOf(translation[0]), "b", String.valueOf(translation[1]))
            );
            tileSpec.setFilterSpec(filterSpec);
        }

        LOG.info("intensityCorrectTiles: exit");
        return tiles;
    }

    private double computeMeanIntensity(
            final TileSpec tileSpec,
            final Rectangle2D roi,
            final ImageProcessorCache cache
    ) {
        // Get the pixels within the overlap region
        final ImageProcessor ip = loadImageProcessor(cache, tileSpec);
        final int roiMinX = (int) (roi.getMinX() - tileSpec.getMinX());
        final int roiMinY = (int) (roi.getMinY() - tileSpec.getMinY());
        ip.setRoi(roiMinX, roiMinY, (int) roi.getWidth(), (int) roi.getHeight());
        final ImageProcessor roiIp = ip.crop();

        // Compute the mean intensity in the ROI
        double sum = 0.0;
        int count = 0;
        for (int y = 0; y < roiIp.getHeight(); y++) {
            for (int x = 0; x < roiIp.getWidth(); x++) {
                sum += roiIp.getf(x, y);
                count++;
            }
        }

        return count > 0 ? sum / count : 0.0;
    }

    /**
     * Save the aligned tiles to the prealigned stack.
     */
    private int saveMfovTiles(final RenderDataClient dataClient,
                              final ResolvedTileSpecCollection alignedTiles) throws IOException {

        if (alignedTiles.getTileCount() == 0) {
            LOG.warn("saveMfovTiles: no aligned tiles to save for mfov={}, z={}",
                     layerMfov.getName(), layerMfov.getZ());
            return 0;
        }

        // Save tiles to the prealigned stack
        dataClient.saveResolvedTiles(alignedTiles, prealignedStackId.getStack(), layerMfov.getZ());

        return alignedTiles.getTileCount();
    }

    private static final Logger LOG = LoggerFactory.getLogger(MfovPrealignTask.class);
}
