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
import java.util.stream.Collectors;

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
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;

/**
 * Serializable task for aligning SFOVs within a single MFOV.
 * This task handles fetching tile specs, deriving matches, optimizing tiles, and saving results.
 */
public class MfovPrealignTask implements Serializable {

    private final String baseDataUrl;
    private final StackId rawSfovStackId;
    private final StackId prealignedStackId;
    private final LayerMFOV layerMfov;
    private final MatchCollectionId matchCollectionId;

    private static final int CLIP_SIZE = 150;

    public MfovPrealignTask(
            final String baseDataUrl,
            final StackId rawSfovStackId,
            final StackId prealignedStackId,
            final LayerMFOV layerMfov,
            final MatchCollectionId matchCollectionId
    ) {
        this.baseDataUrl = baseDataUrl;
        this.rawSfovStackId = rawSfovStackId;
        this.prealignedStackId = prealignedStackId;
        this.layerMfov = layerMfov;
        this.matchCollectionId = matchCollectionId;
    }

    public String toString() {
        return prealignedStackId.toDevString() + "::" + layerMfov;
    }

    /**
     * Align and intensity correct all SFOVs within this MFOV and save the aligned tile specs to the prealigned stack.
     * Both alignment and intensity correction are performed by simple translation models.
     *
     * @throws IOException if alignment fails
     */
    public void run()
            throws IOException {

        final long startTime = System.currentTimeMillis();
        setupLogging();
        LOG.info("run: entry, {}", this);

        final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                 rawSfovStackId.getOwner(),
                                                                 rawSfovStackId.getProject());

        // Initialize the image processor cache with ~1Gb size to hold all tiles in memory
        final ImageProcessorCache cache = new ImageProcessorCache(1_000_000_000L,
                                                                  false,
                                                                  false);

        final ResolvedTileSpecsWithMatchPairs sfovTilesAndMatchesForMfov = fetchSfovTileSpecsWithMatchPairs(dataClient,
                                                                                                            cache);
        final ResolvedTileSpecCollection alignedTiles = alignTiles(sfovTilesAndMatchesForMfov);

        final List<OrderedCanvasIdPair> tilePairs = sfovTilesAndMatchesForMfov.getMatchPairs().stream()
                .map(CanvasMatches::toOrderedPair)
                .collect(Collectors.toList());
        final ResolvedTileSpecCollection alignedIcTiles = intensityCorrectTiles(alignedTiles,
                                                                                tilePairs,
                                                                                cache);

        dataClient.saveResolvedTiles(alignedIcTiles, prealignedStackId.getStack(), layerMfov.getZ());

        final long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
        LOG.info("run: exit, {}, elapsedSeconds={}", this, elapsedSeconds);
    }

    private void setupLogging() {

        // remove info messages for these classes to reduce messages logged per MFOV from ~3100 to ~30
        final String[] reducedLoggerNames = {
                CanvasFeatureExtractor.class.getName(),
                CanvasFeatureMatcher.class.getName(),
                MatchFilter.class.getName()
        };

        final ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        for (final String loggerName : reducedLoggerNames) {

            if (factory instanceof LoggerContext) {

                // Janelia Spark clusters use logback
                final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
                final ch.qos.logback.classic.Logger logger = loggerContext.getLogger(loggerName);
                if (logger == null) {
                    throw new IllegalArgumentException("logger with name '" + loggerName + "' not found");
                }
                logger.setLevel(Level.WARN);

            } else if ("org.apache.logging.slf4j.Log4jLoggerFactory".equals(factory.getClass().getName())) {

                // Google Dataproc Spark clusters use Log4j
                org.apache.logging.log4j.core.config.Configurator.setLevel(loggerName,
                                                                           org.apache.logging.log4j.Level.WARN);

            }
        }

        // Setup executor log4j for runs at Janelia which will place layerMfovDevString in the %X{context} element.
        // For Logs Explorer views of Google Dataproc runs, the context does not seem to be available/selectable
        // as a summary field.  To work around this limitation, the layerMfovDevString is logged explicitly
        // from methods in this class.
        // You can then see which executorId maps to layerMfovDevString and filter accordingly in Logs Explorer.
        LogUtilities.setupExecutorLog4j(this.toString());
    }

    /**
     * Fetch tile specs for all SFOVs belonging to this MFOV at the specified z layer
     * and fetch (or build) the corresponding matches.
     */
    private ResolvedTileSpecsWithMatchPairs fetchSfovTileSpecsWithMatchPairs(final RenderDataClient dataClient,
                                                                             final ImageProcessorCache cache)
            throws IOException {

        LOG.info("fetchSfovTileSpecsWithMatchPairs: entry, {}", this);

        final String matchPattern = "_" + layerMfov.getSimpleMfovName() + "_"; // limit to tiles in this MFOV

        final ResolvedTileSpecsWithMatchPairs resolvedTileSpecsWithMatchPairs;
        if (matchCollectionId == null) {

            final ResolvedTileSpecCollection tiles = dataClient.getResolvedTiles(rawSfovStackId.getStack(),
                                                                                 layerMfov.getZ(),
                                                                                 matchPattern);
            if (tiles.getTileCount() == 0) {
                throw new IOException("no tile specs found for " + this);
            }

            final List<OrderedCanvasIdPair> tilePairs = generateTilePairs(tiles);
            if (tilePairs.isEmpty()) {
                throw new IOException("no tile pairs generated for " + this);
            }

            resolvedTileSpecsWithMatchPairs = generateAndAddMatches(tiles,
                                                                    tilePairs,
                                                                    cache);
        } else {

            final Double z = layerMfov.getZ();
            final Bounds layerBounds = new Bounds(-Double.MAX_VALUE, -Double.MAX_VALUE, z,
                                                  Double.MAX_VALUE, Double.MAX_VALUE, z);

            resolvedTileSpecsWithMatchPairs = dataClient.getResolvedTilesWithMatchPairs(rawSfovStackId.getStack(),
                                                                                        layerBounds,
                                                                                        matchCollectionId.getName(),
                                                                                        null,
                                                                                        matchPattern,
                                                                                        false);
            if (resolvedTileSpecsWithMatchPairs.getResolvedTileSpecs().getTileCount() == 0) {
                throw new IOException("no tile specs found for " + this);
            }

            final int originalMatchPairCount = resolvedTileSpecsWithMatchPairs.getMatchPairCount();
            resolvedTileSpecsWithMatchPairs.removeMatchPairsThatReferenceTilesOutsideThisCollection();
            final int normalizedMatchPairCount = resolvedTileSpecsWithMatchPairs.getMatchPairCount();
            if (normalizedMatchPairCount < originalMatchPairCount) {
                LOG.info("fetchSfovTileSpecsWithMatchPairs: removed {} pairs and kept {} pairs for {}",
                         (originalMatchPairCount - normalizedMatchPairCount), normalizedMatchPairCount, this);
            }

            // change matches to world coordinates to be consistent with original manual derivation approach
            resolvedTileSpecsWithMatchPairs.changeMatchesToWorldCoordinates();
        }

        if (resolvedTileSpecsWithMatchPairs.getMatchPairCount() == 0) {
            throw new IOException("no matches were found or built for " + this);
        }

        LOG.info("fetchSfovTileSpecsWithMatchPairs: {} returning {} tiles and {} match pairs",
                 this,
                 resolvedTileSpecsWithMatchPairs.getResolvedTileSpecs().getTileCount(),
                 resolvedTileSpecsWithMatchPairs.getMatchPairCount());

        return resolvedTileSpecsWithMatchPairs;
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
                    final CanvasId canvasIdI = new CanvasId(ti.getZ().toString(), ti.getTileId());
                    final CanvasId canvasIdJ = new CanvasId(tj.getZ().toString(), tj.getTileId());
                    pairs.add(new OrderedCanvasIdPair(canvasIdI, canvasIdJ, 0.0));
                }
            }
        }

        return pairs;
    }

    /**
     * Perform SIFT feature matching on the tile pairs.
     */
    private ResolvedTileSpecsWithMatchPairs generateAndAddMatches(final ResolvedTileSpecCollection tiles,
                                                                  final List<OrderedCanvasIdPair> tilePairs,
                                                                  final ImageProcessorCache cache) {

        final long startTime = System.currentTimeMillis();

        LOG.info("generateAndAddMatches: entry, {}", this);

        final List<CanvasMatches> matchPairs = new ArrayList<>();

        // Extract features from all mfov tiles
        final CanvasFeatureExtractor featureExtractor = CanvasFeatureExtractor.build(FEATURE_EXTRACTION_PARAMETERS);
        final Map<String, List<Feature>> mfovFeatures = new HashMap<>(tiles.getTileCount());

        for (final TileSpec tileSpec : tiles.getTileSpecs()) {
            final List<Feature> tileFeatures = extractBoundaryFeatures(cache, tileSpec, featureExtractor);
            mfovFeatures.put(tileSpec.getTileId(), tileFeatures);
        }

        // Match features between tile pairs
        final double renderScale = 1.0;
        final CanvasFeatureMatcher featureMatcher = new CanvasFeatureMatcher(MATCH_DERIVATION_PARAMETERS,
                                                                             renderScale);

        CanvasMatchResult matchResult;
        for (final OrderedCanvasIdPair pair : tilePairs) {

            final CanvasId p = pair.getP();
            final CanvasId q = pair.getQ();

            final String pTileId = p.getId();
            final String qTileId = q.getId();

            matchResult = featureMatcher.deriveMatchResult(mfovFeatures.get(pTileId),
                                                           mfovFeatures.get(qTileId));

            // create fake connection if no inliers found
            if (matchResult == null || matchResult.getTotalNumberOfInliers() == 0) {
                final PointMatch fakeMatch = new PointMatch(new Point(new double[]{0.0, 0.0}),
                                                            new Point(new double[]{0.0, 0.0}),
                                                            1e-4);
                final List<PointMatch> fakeList = new ArrayList<>();
                fakeList.add(fakeMatch);
                final List<List<PointMatch>> fakeInliers = new ArrayList<>();
                fakeInliers.add(fakeList);
                matchResult = new CanvasMatchResult(null, fakeInliers, 1);
            }

            final List<CanvasMatches> canvasMatchesList = matchResult.getInlierMatchesList(p.getGroupId(),
                                                                                           p.getId(),
                                                                                           q.getGroupId(),
                                                                                           q.getId(),
                                                                                           renderScale,
                                                                                           CanvasId.ZERO_OFFSETS,
                                                                                           CanvasId.ZERO_OFFSETS);
            matchPairs.add(canvasMatchesList.get(0));
        }

        final long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;

        LOG.info("generateAndAddMatches: exit, {}, elapsedSeconds={}", this, elapsedSeconds);

        return new ResolvedTileSpecsWithMatchPairs(tiles, matchPairs);
    }

    /**
     * Align tile pairs.
     */
    private ResolvedTileSpecCollection alignTiles(final ResolvedTileSpecsWithMatchPairs sfovTilesAndMatchesForMfov) {

        final long startTime = System.currentTimeMillis();
        LOG.info("alignTiles: entry, {}", this);

        final ResolvedTileSpecCollection tiles = sfovTilesAndMatchesForMfov.getResolvedTileSpecs();

        final Map<String, Tile<TranslationModel2D>> modelTiles = new HashMap<>();

        for (final TileSpec tileSpec : sfovTilesAndMatchesForMfov.getResolvedTileSpecs().getTileSpecs()) {
            modelTiles.put(tileSpec.getTileId(), new Tile<>(new TranslationModel2D()));
        }

        int connectedPairCount = 0;
        for (final CanvasMatches canvasMatches : sfovTilesAndMatchesForMfov.getMatchPairs()) {
            // Connect tiles for optimization...
            final Tile<TranslationModel2D> pModelTile = modelTiles.get(canvasMatches.getpId());
            final Tile<TranslationModel2D> qModelTile = modelTiles.get(canvasMatches.getqId());

            if (pModelTile == null || qModelTile == null) {
                LOG.info("alignTiles: skipping pair {} in {}", canvasMatches.toKeyString(), this);
                continue;
            }
            final List<PointMatch> pointMatches = CanvasMatchResult.convertMatchesToPointMatchList(canvasMatches.getMatches());
            pModelTile.connect(qModelTile, pointMatches);
            connectedPairCount++;
        }

        LOG.info("alignTiles: start optimization after connecting {} pairs, {}",
                 connectedPairCount, this);

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

        final long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
        LOG.info("alignTiles: exit, {}, returning collection with {} tiles, elapsedSeconds={}",
                 this, tiles.getTileCount(), elapsedSeconds);

        return tiles;
    }

    /**
     * Extract features from boundary regions.
     */
    private List<Feature> extractBoundaryFeatures(
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

        /*
         NOTE: this will get written for every tile in every pair so only include it to debug issues
         LOG.info("extractBoundaryFeatures: exit, returning {} features for tile ID {}",
                  features.size(), tileSpec.getTileId());
        */
        return features;
    }

    private ImageProcessor loadImageProcessor(
            final ImageProcessorCache cache,
            final TileSpec tileSpec
    ) {
        final ImageAndMask imageAndMask = tileSpec.getFirstMipmapEntry().getValue();
        return cache.get(imageAndMask.getImageUrl(),
                         0,
                         false,
                         false,
                         imageAndMask.getImageLoaderType(),
                         0);
        /*
         NOTE: this will get written for every tile in every pair so only include it to debug issues
         final String size = ip != null ? (ip.getWidth() + "x" + ip.getHeight()) : "null";
         LOG.debug("loadImageProcessor: exit, returning {} image for tile ID {}",
                   size, tileSpec.getTileId());
         return ip;
        */
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

        LOG.info("intensityCorrectTiles: entry with {} tiles and {} match pairs for {}",
                 tiles.getTileCount(),
                 tilePairs.size(),
                 this);

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

            final Rectangle2D rectI = new Rectangle2D.Double(tileSpecI.getMinX(), tileSpecI.getMinY(), tileSpecI.getWidth(), tileSpecI.getHeight());
            final Rectangle2D rectJ = new Rectangle2D.Double(tileSpecJ.getMinX(), tileSpecJ.getMinY(), tileSpecJ.getWidth(), tileSpecJ.getHeight());

            if (rectI.intersects(rectJ)) {

                // Compute means on the overlap
                final Rectangle2D overlap = rectI.createIntersection(rectJ);

                final double meanI = computeMeanIntensity(tileSpecI, overlap, cache);
                final double meanJ = computeMeanIntensity(tileSpecJ, overlap, cache);

                // Add point match
                final PointMatch pointMatch = new PointMatch(
                        new Point(new double[]{meanI}),
                        new Point(new double[]{meanJ})
                );
                final Tile<TranslationModel1D> modelTileI = modelTiles.get(tileIdI);
                final Tile<TranslationModel1D> modelTileJ = modelTiles.get(tileIdJ);
                modelTileI.connect(modelTileJ, Collections.singletonList(pointMatch));

            } else {
                LOG.warn("intensityCorrectTiles: tiles {} and {} do not overlap after alignment of {}",
                         tileSpecI.toTileBounds(), tileSpecJ.toTileBounds(), this);
            }
        }

        // Optimize the translation models for intensity correction
        final TileConfiguration tc = new TileConfiguration();
        tc.addTiles(modelTiles.values());
        tc.fixTile(modelTiles.values().stream().findFirst().orElseThrow());

        try {
            final ErrorStatistic errorStatistic = new ErrorStatistic(1001);
            tc.optimizeSilently(errorStatistic, 0.5, 1000, 100);
        } catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
            throw new RuntimeException("Failed to optimize intensity correction for " + this, e);
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

        LOG.info("intensityCorrectTiles: exit, {}", this);
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

    private static FeatureExtractionParameters buildFeatureExtractionParameters() {
        final FeatureExtractionParameters p = new FeatureExtractionParameters();
        p.fdSize = 4;
        p.maxScale = 1.0;
        p.minScale = 0.25;
        p.steps = 5;
        return p;
    }
    private static final FeatureExtractionParameters FEATURE_EXTRACTION_PARAMETERS = buildFeatureExtractionParameters();

    private static MatchDerivationParameters buildMatchDerivationParameters() {
        final MatchDerivationParameters p = new MatchDerivationParameters();
        p.matchFilter = MatchFilter.FilterType.SINGLE_SET;
        p.matchFullScaleCoverageRadius = 300.0;
        p.matchIterations = 1000;
        p.matchMaxEpsilonFullScale = 5.0f;
        p.matchMaxTrust = 4.0;
        p.matchMinCoveragePercentage = 0.0;
        p.matchMinInlierRatio = 0.0f;
        p.matchMinNumInliers = 10;
        p.matchModelType = ModelType.TRANSLATION;
        p.matchRod = 0.92f;
        return p;
    }
    private static final MatchDerivationParameters MATCH_DERIVATION_PARAMETERS = buildMatchDerivationParameters();

    private static final Logger LOG = LoggerFactory.getLogger(MfovPrealignTask.class);
}
