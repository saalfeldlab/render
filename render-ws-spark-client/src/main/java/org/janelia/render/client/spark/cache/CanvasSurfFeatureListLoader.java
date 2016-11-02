package org.janelia.render.client.spark.cache;

import javax.annotation.Nonnull;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasSurfFeatureExtractor;
import org.janelia.alignment.match.SurfFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts SURF features for a canvas and loads them into the cache.
 *
 * @author Eric Trautman
 */
public class CanvasSurfFeatureListLoader
        extends CanvasDataLoader {

    private final CanvasSurfFeatureExtractor featureExtractor;

    /**
     * @param  renderParametersUrlTemplate  template for deriving render parameters URL for each canvas.*
     * @param  featureExtractor             configured feature extractor.
     */
    public CanvasSurfFeatureListLoader(final String renderParametersUrlTemplate,
                                       final CanvasSurfFeatureExtractor featureExtractor) {
        super(renderParametersUrlTemplate, CachedCanvasSurfFeatures.class);
        this.featureExtractor = featureExtractor;
    }

    @Override
    public CachedCanvasSurfFeatures load(@Nonnull final CanvasId canvasId) throws Exception {

        LOG.info("load: extracting features for {}", canvasId);

        final SurfFeatures featureList = featureExtractor.extractFeatures(getRenderParameters(canvasId), null);

        LOG.info("load: exit");

        return new CachedCanvasSurfFeatures(featureList);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasSurfFeatureListLoader.class);
}
