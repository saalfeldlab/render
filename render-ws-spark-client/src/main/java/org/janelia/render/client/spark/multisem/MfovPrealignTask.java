package org.janelia.render.client.spark.multisem;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import ij.process.ImageProcessor;
import mpicbg.imagefeatures.Feature;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel2D;

import net.imglib2.img.io.Load;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
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
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.MFOVAsTileParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.crypto.dsig.Transform;

/**
 * Serializable task for aligning SFOVs within a single MFOV.
 * This task handles fetching tile specs, deriving matches, optimizing tiles, and saving results.
 */
public class MfovPrealignTask implements Serializable {

    private final String baseDataUrl;
    private final StackId rawSfovStackId;
    private final StackId prealignedStackId;
    private final LayerMFOV layerMfov;
    private final MFOVAsTileParameters mfovAsTile;

	private static final FeatureExtractionParameters FEATURE_EXTRACTION_PARAMETERS = new FeatureExtractionParameters();
	private static final MatchDerivationParameters MATCH_DERIVATION_PARAMETERS = new MatchDerivationParameters();
	private static final int CLIP_SIZE = 150;

    public MfovPrealignTask(final String baseDataUrl,
                            final StackId rawSfovStackId,
                            final StackId prealignedStackId,
                            final LayerMFOV layerMfov,
                            final MFOVAsTileParameters mfovAsTile) {
        this.baseDataUrl = baseDataUrl;
        this.rawSfovStackId = rawSfovStackId;
        this.prealignedStackId = prealignedStackId;
        this.layerMfov = layerMfov;
        this.mfovAsTile = mfovAsTile;

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
     * Align all SFOVs within this MFOV and save the aligned tile specs to the prealigned stack.
     *
     * @throws RuntimeException if alignment fails
     */
    public void alignMfov() {
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

            // 2. Align tiles within this MFOV
            final ResolvedTileSpecCollection alignedTiles = alignTiles(mfovTiles);

            // 4. Push the aligned tile specs to the prealigned stack
            final int savedTileCount = saveMfovTiles(dataClient, alignedTiles);

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
        final ResolvedTileSpecCollection mfovTiles = dataClient.getResolvedTiles(rawSfovStackId.getStack(), layerMfov.getZ());

        // Filter tiles that belong to this MFOV and add them to the collection
        final Set<String> tileIdsToKeep = mfovTiles.getTileSpecs().stream()
                .map(TileSpec::getTileId)
                .filter(tileId -> tileId.contains("_" + layerMfov.getSimpleMfovName() + "_"))
                .collect(Collectors.toSet());
        mfovTiles.retainTileSpecs(tileIdsToKeep);

        return mfovTiles;
    }

    /**
     * Derive matches between neighboring SFOVs within this MFOV.
     */
    private ResolvedTileSpecCollection alignTiles(final ResolvedTileSpecCollection mfovTiles) {
		// 1. Generate tile pairs for matching
		final List<OrderedCanvasIdPair> tilePairs = generateTilePairs(mfovTiles);

		if (tilePairs.isEmpty()) {
			LOG.warn("alignTiles: no tile pairs generated");
			return mfovTiles;
		}
		LOG.info("alignTiles: generated {} tile pairs", tilePairs.size());

		// 2. Perform SIFT feature matching on tile pairs
		final ResolvedTileSpecCollection optimizedTiles = performSiftAlignment(mfovTiles, tilePairs);
		LOG.info("alignTiles: exit");
		return optimizedTiles;
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

				final boolean intersects = rectI.intersects(rectJ);
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
	private ResolvedTileSpecCollection performSiftAlignment(
			final ResolvedTileSpecCollection mfovTiles,
			final List<OrderedCanvasIdPair> tilePairs
	) {
		// Set cache size to ~1GB, so that all mfov tiles can be kept in memory
		final ImageProcessorCache ipCache = new ImageProcessorCache(1_000_000_000L, false, false);

		// Extract features from all mfov tiles
		final CanvasFeatureExtractor featureExtractor = CanvasFeatureExtractor.build(FEATURE_EXTRACTION_PARAMETERS);
		final Map<String, List<Feature>> mfovFeatures = new HashMap<>(mfovTiles.getTileCount());
		final Map<String, Tile<TranslationModel2D>> tiles = new HashMap<>();

		for (final TileSpec tileSpec : mfovTiles.getTileSpecs()) {
			final List<Feature> tileFeatures = extractBoundaryFeatures(ipCache, tileSpec, featureExtractor);

			mfovFeatures.put(tileSpec.getTileId(), tileFeatures);
			tiles.put(tileSpec.getTileId(), new Tile<>(new TranslationModel2D()));
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
			final Tile<TranslationModel2D> tileI = tiles.get(tileIdI);
			final Tile<TranslationModel2D> tileJ = tiles.get(tileIdJ);
			if (matchResult == null || matchResult.getTotalNumberOfInliers() == 0) {
				// ... with fake matches if no inliers found
				final PointMatch fakeMatch = new PointMatch(
						new Point(new double[] {0.0, 0.0}), new Point(new double[] {0.0, 0.0}), 1e-4
				);
				tileI.connect(tileJ, Collections.singletonList(fakeMatch));
			} else {
				// ... with real matches if inliers found
				final List<PointMatch> pointMatches = matchResult.getInlierPointMatchList();
				tileI.connect(tileJ, pointMatches);
			}
		}

		// Optimize the tiles
		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles(tiles.values());
		tc.fixTile(tiles.values().stream().findFirst().orElseThrow());

		try {
			final ErrorStatistic errorStatistic = new ErrorStatistic(1001);
			tc.optimizeSilently(errorStatistic, 0.5, 5000, 1000);
		} catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			throw new RuntimeException("Failed to optimize tile transformations", e);
		}


		// Add the transformations to the tiles
		final double[] translation = new double[6];
		for (final Map.Entry<String, Tile<TranslationModel2D>> entry : tiles.entrySet()) {
			final String tileId = entry.getKey();
			final TranslationModel2D model = entry.getValue().getModel();

			// Combine the translation from the model with the previous translation
			model.toArray(translation);
			final LeafTransformSpec previousTransformSpec = (LeafTransformSpec) mfovTiles.getTileSpec(tileId).getLastTransform();

			final String[] previousCoefficients = previousTransformSpec.getDataString().split(" ");
			final double x = Double.parseDouble(previousCoefficients[4]) + translation[4];
			final double y = Double.parseDouble(previousCoefficients[5]) + translation[5];
			final LeafTransformSpec newTransformSpec =  new LeafTransformSpec(
					"mpicbg.trakem2.transform.AffineModel2D",
					"1 0 0 1 " + x + " " + y
			);

			// Set the transformation model to the tile spec
			mfovTiles.addTransformSpecToTile(tileId, newTransformSpec, ResolvedTileSpecCollection.TransformApplicationMethod.REPLACE_LAST);
		}

		return mfovTiles;
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
		final int downSampleLevel = 0;
		final ChannelSpec firstChannel = tileSpec.getAllChannels().get(0);
		final ImageAndMask image = firstChannel.getMipmap(downSampleLevel);
		final ImageProcessor ip = cache.get(image.getImageUrl(), downSampleLevel, false, false, image.getImageLoaderType(), 0);
		final int width = ip.getWidth();
		final int height = ip.getHeight();

		// Left
		ip.setRoi(0, 0, CLIP_SIZE, height);
		final List<Feature> features = new ArrayList<>(extractFeaturesFromRoi(featureExtractor, ip, 0, 0));
		// Right
		ip.setRoi(width - CLIP_SIZE, 0, CLIP_SIZE, height);
		features.addAll(extractFeaturesFromRoi(featureExtractor, ip, width - CLIP_SIZE, 0));
		// Top
		ip.setRoi(CLIP_SIZE, 0, width - 2 * CLIP_SIZE, CLIP_SIZE);
		features.addAll(extractFeaturesFromRoi(featureExtractor, ip, CLIP_SIZE, 0));
		// Bottom
		ip.setRoi(CLIP_SIZE, height - CLIP_SIZE, width - 2 * CLIP_SIZE, CLIP_SIZE);
		features.addAll(extractFeaturesFromRoi(featureExtractor, ip, CLIP_SIZE, height));

		// Move the features to the global coordinate system
		for (final Feature feature : features) {
			feature.location[0] += tileSpec.getMinX();
			feature.location[1] += tileSpec.getMinY();
		}

		return features;
	}

	/**
	 * Extract features from ROI and shift them
	 */
	private static List<Feature> extractFeaturesFromRoi(
			final CanvasFeatureExtractor featureExtractor,
			final ImageProcessor ip,
			final int xShift,
			final int yShift
	) {
		final ImageProcessor roiIp = ip.crop();
		final List<Feature> features = featureExtractor.extractFeaturesFromImageAndMask(roiIp, null);
		for (final Feature feature : features) {
			feature.location[0] += xShift;
			feature.location[1] += yShift;
		}
		return features;
	}

    /**
     * Optimize tile transformations using the derived matches.
     */
    private ResolvedTileSpecCollection optimizeTileTransformations(final ResolvedTileSpecCollection mfovTiles,
                                                                  final List<CanvasMatches> matches) {
        LOG.info("optimizeTileTransformations: applying basic local optimization for {} tiles with {} matches",
                 mfovTiles.getTileCount(), matches.size());

        // For now, return the original tiles without optimization
        // This is a placeholder - you can implement actual tile optimization here
        // using the Tile<TranslationModel2D> objects created in performSiftMatching

        return mfovTiles;
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
