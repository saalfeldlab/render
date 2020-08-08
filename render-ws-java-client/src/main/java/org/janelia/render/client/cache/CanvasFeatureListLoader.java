package org.janelia.render.client.cache;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import mpicbg.imagefeatures.Feature;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureList;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
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

    /**
     * @param  urlTemplate                  template for deriving render parameters URL for each canvas.
     * @param  featureExtractor             configured feature extractor.
     */
    public CanvasFeatureListLoader(final CanvasRenderParametersUrlTemplate urlTemplate,
                                   final CanvasFeatureExtractor featureExtractor) {
        this(urlTemplate, featureExtractor, null, false);
    }

    /**
     * @param  urlTemplate                  template for deriving render parameters URL for each canvas.
     *
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
    public CanvasFeatureListLoader(final CanvasRenderParametersUrlTemplate urlTemplate,
                                   final CanvasFeatureExtractor featureExtractor,
                                   final File rootFeatureStorageDirectory,
                                   final boolean requireStoredFeatures) {
        super(urlTemplate, CachedCanvasFeatures.class);
        this.featureExtractor = featureExtractor;
        this.rootFeatureStorageDirectory =rootFeatureStorageDirectory;
        this.requireStoredFeatures = requireStoredFeatures;
    }

    @Override
    public CachedCanvasFeatures load(final CanvasId canvasId) {

        List<Feature> featureList = null;
        double[] offsets = null;

        if (rootFeatureStorageDirectory != null) {

            final Path storagePath = CanvasFeatureList.getStoragePath(rootFeatureStorageDirectory, canvasId);

            CanvasFeatureList canvasFeatureList = null;
            try {

                canvasFeatureList = CanvasFeatureList.readFromStorage(rootFeatureStorageDirectory, canvasId);

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
                checkCompatibility("canvas ids", canvasId, storedCanvasId, storagePath);

                final String storedUrl = canvasFeatureList.getRenderParametersUrl();
                checkCompatibility("render parameters URLs", getRenderParametersUrl(canvasId), storedUrl, storagePath);
                checkCompatibility("clip width", getClipWidth(), canvasFeatureList.getClipWidth(), storagePath);
                checkCompatibility("clip height", getClipHeight(), canvasFeatureList.getClipHeight(), storagePath);

                featureList = canvasFeatureList.getFeatureList();
                offsets = storedCanvasId.getClipOffsets();
            }

        }

        if (featureList == null) {
            final RenderParameters renderParameters = getRenderParameters(canvasId);
            offsets = canvasId.getClipOffsets(); // HACK WARNING: offsets get applied by getRenderParameters call

            LOG.info("load: extracting features for {} with offsets ({}, {})", canvasId, offsets[0], offsets[1]);
            featureList = featureExtractor.extractFeatures(renderParameters, null);
        }

        LOG.info("load: exit");

        return new CachedCanvasFeatures(featureList, offsets);
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
