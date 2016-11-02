package org.janelia.render.client.spark.cache;

import java.util.List;

import javax.annotation.Nonnull;

import mpicbg.imagefeatures.Feature;

import org.janelia.alignment.match.CanvasSiftFeatureExtractor;
import org.janelia.alignment.match.CanvasId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts SIFT features for a canvas and loads them into the cache.
 *
 * @author Eric Trautman
 */
public class CanvasSiftFeatureListLoader
        extends CanvasDataLoader {

    private final CanvasSiftFeatureExtractor featureExtractor;

    /**
     * @param  renderParametersUrlTemplate  template for deriving render parameters URL for each canvas.*
     * @param  featureExtractor             configured feature extractor.
     */
    public CanvasSiftFeatureListLoader(final String renderParametersUrlTemplate,
                                       final CanvasSiftFeatureExtractor featureExtractor) {
        super(renderParametersUrlTemplate, CachedCanvasSiftFeatures.class);
        this.featureExtractor = featureExtractor;
    }

    @Override
    public CachedCanvasSiftFeatures load(@Nonnull final CanvasId canvasId) throws Exception {

        LOG.info("load: extracting features for {}", canvasId);

        final List<Feature> featureList = featureExtractor.extractFeatures(getRenderParameters(canvasId), null);

        LOG.info("load: exit");

        return new CachedCanvasSiftFeatures(featureList);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasSiftFeatureListLoader.class);
}
