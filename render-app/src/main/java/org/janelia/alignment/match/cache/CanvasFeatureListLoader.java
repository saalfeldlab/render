package org.janelia.alignment.match.cache;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nonnull;

import mpicbg.imagefeatures.Feature;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureExtractor.FeaturesWithSourceData;
import org.janelia.alignment.match.CanvasFeatureList;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts features for a canvas and loads them into the cache.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureListLoader
        extends CanvasDataLoader {

    private final CanvasFeatureExtractor featureExtractor;
    private final File rootFeatureStorageDirectory;
    private final boolean requireStoredFeatures;
    private transient boolean isSourceDataCachingEnabled;

    /**
     * @param  featureExtractor             configured feature extractor.
     */
    public CanvasFeatureListLoader(final CanvasFeatureExtractor featureExtractor) {
        this(featureExtractor, null, false);
    }

    /**
     * @param  featureExtractor             configured feature extractor.
     *
     * @param  rootFeatureStorageDirectory  root directory for persisted feature list data
     *                                      (or null if features should always be extracted
     *                                      from a dynamically rendered canvas).
     *
     * @param  requireStoredFeatures        if true, exception will be thrown when stored features
     *                                      for a canvas cannot be found on disk;
     *                                      if false, stored features will be loaded from disk
     *                                      but missing features will be extracted from a dynamically rendered canvas.
     */
    public CanvasFeatureListLoader(final CanvasFeatureExtractor featureExtractor,
                                   final File rootFeatureStorageDirectory,
                                   final boolean requireStoredFeatures) {
        super(CachedCanvasFeatures.class);
        this.featureExtractor = featureExtractor;
        this.rootFeatureStorageDirectory =rootFeatureStorageDirectory;
        this.requireStoredFeatures = requireStoredFeatures;
        this.isSourceDataCachingEnabled = false;
    }

    public void enableSourceDataCaching(final ImageProcessorCache imageProcessorCache) {
        this.isSourceDataCachingEnabled = true;
        featureExtractor.setImageProcessorCache(imageProcessorCache);
    }

    @Override
    public CachedCanvasFeatures load(@Nonnull final CanvasIdWithRenderContext canvasIdWithRenderContext) {

        List<Feature> featureList = null;
        double[] offsets = null;

        if (rootFeatureStorageDirectory != null) {

            final Path storagePath = CanvasFeatureList.getStoragePath(rootFeatureStorageDirectory,
                                                                      canvasIdWithRenderContext.getCanvasId());

            CanvasFeatureList canvasFeatureList = null;
            try {

                canvasFeatureList = CanvasFeatureList.readFromStorage(rootFeatureStorageDirectory,
                                                                      canvasIdWithRenderContext.getCanvasId());

                LOG.info("loaded {} features from {}", canvasFeatureList.size(), storagePath);

            } catch (final Exception e) {

                final String message = "failed to load features from " + storagePath;
                if (requireStoredFeatures) {
                    throw new IllegalStateException(message, e);
                } else {
                    LOG.warn(message, e);
                }

            }

            if (canvasFeatureList != null) {

                final CanvasId storedCanvasId = canvasFeatureList.getCanvasId();
                checkCompatibility("canvas ids",
                                   canvasIdWithRenderContext.getCanvasId(),
                                   storedCanvasId,
                                   storagePath);
                checkCompatibility("render parameters URLs",
                                   canvasIdWithRenderContext.getUrl(),
                                   canvasFeatureList.getRenderParametersUrl(),
                                   storagePath);
                checkCompatibility("clip width",
                                   canvasIdWithRenderContext.getClipWidth(),
                                   canvasFeatureList.getClipWidth(),
                                   storagePath);
                checkCompatibility("clip height",
                                   canvasIdWithRenderContext.getClipHeight(),
                                   canvasFeatureList.getClipHeight(),
                                   storagePath);

                featureList = canvasFeatureList.getFeatureList();
                offsets = storedCanvasId.getClipOffsets();
            }

        }

        final CachedCanvasFeatures cachedCanvasFeatures;
        if (featureList == null) {

            final RenderParameters renderParameters = canvasIdWithRenderContext.loadRenderParameters();
            offsets = canvasIdWithRenderContext.getClipOffsets(); // warning: must call this after loadRenderParameters

            if (isSourceDataCachingEnabled) {
                final FeaturesWithSourceData featuresWithSourceData =
                        featureExtractor.extractFeaturesWithSourceData(renderParameters);
                cachedCanvasFeatures = new CachedCanvasFeatures(featuresWithSourceData, offsets);
            } else {
                featureList = featureExtractor.extractFeatures(renderParameters, null);
                cachedCanvasFeatures = new CachedCanvasFeatures(featureList, offsets);
            }

        } else {
            cachedCanvasFeatures = new CachedCanvasFeatures(featureList, offsets);
        }

        LOG.info("load: exit");

        return cachedCanvasFeatures;
    }

    private void checkCompatibility(final String context,
                                    final Object expectedValue,
                                    final Object actualValue,
                                    final Path storagePath) throws IllegalStateException {

        final boolean notCompatible;
        if (expectedValue == null) {
            notCompatible = (actualValue != null);
        } else {
            notCompatible = (! expectedValue.equals(actualValue));
        }

        if (notCompatible) {
            throw new IllegalStateException(
                    "incompatible " + context + ", expected " + expectedValue +
                    " but found " + actualValue + " in " + storagePath);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasFeatureListLoader.class);
}
