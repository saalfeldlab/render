package org.janelia.render.client.spark.cache;

import java.util.List;

import javax.annotation.Nonnull;

import mpicbg.imagefeatures.Feature;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasId;
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

    /**
     * @param  renderParametersUrlTemplate  template for deriving render parameters URL for each canvas.*
     * @param  featureExtractor             configured feature extractor.
     */
    public CanvasFeatureListLoader(final String renderParametersUrlTemplate,
                                   final CanvasFeatureExtractor featureExtractor) {
        super(renderParametersUrlTemplate, CachedCanvasFeatures.class);
        this.featureExtractor = featureExtractor;
    }

    @Override
    public CachedCanvasFeatures load(@Nonnull final CanvasId canvasId) throws Exception {

        final RenderParameters renderParameters = getRenderParameters(canvasId);
        final double[] offsets = canvasId.getClipOffsets();

        LOG.info("load: extracting features for {} with offsets ({}, {})", canvasId, offsets[0], offsets[1]);

        final List<Feature> featureList = featureExtractor.extractFeatures(renderParameters, null);

        LOG.info("load: exit");

        return new CachedCanvasFeatures(featureList);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasFeatureListLoader.class);
}
