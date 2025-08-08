package org.janelia.render.client.spark.multisem;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import ij.process.ImageProcessor;
import mpicbg.imagefeatures.Feature;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
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
                final Rectangle2D rectJ = new Rectangle2D.Double(tj.getMinX(), tj.getMaxY(), tj.getWidth(), tj.getHeight());

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
	private ResolvedTileSpecCollection performSiftAlignment(final ResolvedTileSpecCollection mfovTiles,
															final List<OrderedCanvasIdPair> tilePairs) {

		// Extract features from all mfov tiles
		final FeatureExtractionParameters featureExtractionParameters = MFOVAsTileParameters.buildFeatureExtractionParameters();
		final CanvasFeatureExtractor featureExtractor = CanvasFeatureExtractor.build(featureExtractionParameters);
		final Map<CanvasId, List<Feature>> mfovFeatures = new HashMap<>(mfovTiles.getTileCount());
		final Map<CanvasId, Tile<TranslationModel2D>> tiles = new HashMap<>();

		for (final TileSpec tileSpec : mfovTiles.getTileSpecs()) {
			// Set cache size to ~1GB, so that all mfov tiles can be kept in memory
			final ImageProcessorCache ipCache = new ImageProcessorCache(1_000_000_000L, false, false);
			final ChannelSpec firstChannel = tileSpec.getAllChannels().get(0);
			final ImageAndMask image = firstChannel.getMipmap(0);
			final ImageProcessor ip = ipCache.get(image.getImageUrl(), 0, false, false, image.getImageLoaderType(), null);
			final List<Feature> tileFeatures = featureExtractor.extractFeaturesFromImageAndMask(ip, null);

			final CanvasId canvasId = new CanvasId(tileSpec.getGroupId(), tileSpec.getTileId());
			mfovFeatures.put(canvasId, tileFeatures);
			tiles.put(canvasId, new Tile<>(new TranslationModel2D()));
		}

		// Match features between tile pairs
		final MatchDerivationParameters matchDerivationParameters = MFOVAsTileParameters.buildFeatureMatchDerivation(10);
		final CanvasFeatureMatcher featureMatcher = new CanvasFeatureMatcher(matchDerivationParameters, 1.0);

		for (final OrderedCanvasIdPair pair : tilePairs) {
			final CanvasId canvasIdI = pair.getP();
			final CanvasId canvasIdJ = pair.getQ();

			final CanvasMatchResult matchResult = featureMatcher.deriveMatchResult(
					mfovFeatures.get(canvasIdI),
					mfovFeatures.get(canvasIdJ)
			);

			// Connect tiles for optimization using point matches
			if (matchResult != null && matchResult.getTotalNumberOfInliers() > 0) {
				final List<PointMatch> pointMatches = matchResult.getInlierPointMatchList();
				final Tile<TranslationModel2D> tileI = tiles.get(canvasIdI);
				final Tile<TranslationModel2D> tileJ = tiles.get(canvasIdJ);

				tileI.connect(tileJ, pointMatches);
			}
		}

		// Optimize the tiles
		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles(tiles.values());
		tc.fixTile(tiles.values().stream().findFirst().orElseThrow());

		TileUtil.optimizeConcurrently(
				new ErrorStatistic(1000),
				0.5,
				5000,
				1000,
				0.0f,
				tc,
				tc.getTiles(),
				tc.getFixedTiles(),
				1,
				false
		);

		// Add the transformations to the tiles
		for (final Map.Entry<CanvasId, Tile<TranslationModel2D>> entry : tiles.entrySet()) {
			final String tileId = entry.getKey().getId();
			final TranslationModel2D model = entry.getValue().getModel();

			// Combine the translation from the model with the previous translation
			final double[] translation = new double[2];
			model.toArray(translation);
			final LeafTransformSpec previousTransformSpec = (LeafTransformSpec) mfovTiles.getTileSpec(tileId).getLastTransform();

			final String[] previousCoefficients = previousTransformSpec.getDataString().split(" ");
			final double x = Double.parseDouble(previousCoefficients[4]) + translation[0];
			final double y = Double.parseDouble(previousCoefficients[5]) + translation[1];
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
