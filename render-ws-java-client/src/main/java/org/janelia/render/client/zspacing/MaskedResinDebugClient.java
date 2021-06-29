package org.janelia.render.client.zspacing;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImagePlus;

import java.io.IOException;
import java.util.Collections;

import mpicbg.imglib.multithreading.SimpleMultiThreading;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.zspacing.loader.LayerLoader;
import org.janelia.render.client.zspacing.loader.MaskedResinLayerLoader;
import org.janelia.render.client.zspacing.loader.RenderLayerLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for debugging resin mask parameterization.
 */
public class MaskedResinDebugClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--z",
                description = "Layer to render",
                required = true)
        public Double z;

        @Parameter(
                names = "--scale",
                description = "Scale to render layer",
                required = true)
        public Double scale;

        @Parameter(
                names = "--resinSigma",
                description = "Standard deviation for gaussian convolution")
        public Integer resinSigma = 100;

        @Parameter(
                names = "--resinContentThreshold",
                description = "Threshold intensity that identifies content")
        public Double resinContentThreshold = 3.0;

        @Parameter(
                names = "--resinMaskIntensity",
                description = "Intensity value to use when masking resin areas (typically max intensity for image)")
        public Float resinMaskIntensity = 255.0f;

        @ParametersDelegate
        public LayerBoundsParameters bounds = new LayerBoundsParameters();

        public Parameters() {
        }

    }

    public static void main(String[] args) {

        if (args.length == 0) {
            args = new String[] {
                    "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                    "--owner", "cosem",
                    "--project", "jrc_mus_lung_ctrl",
                    "--stack", "v1_acquire_align",
                    "--z", "1285",
                    "--scale", "0.22",
                    "--resinSigma", "100",
                    "--resinContentThreshold", "3.0",
            };
        }

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.bounds.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final MaskedResinDebugClient client = new MaskedResinDebugClient(parameters);
                client.showSourceAndMask();
            }
        };

        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    MaskedResinDebugClient(final Parameters parameters)
            throws IllegalArgumentException {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    String getLayerUrlPattern()
            throws IOException {

        final RenderWebServiceUrls urls = renderDataClient.getUrls();
        final String stackUrlString = urls.getStackUrlString(parameters.stack);

        final Bounds totalBounds = renderDataClient.getLayerBounds(parameters.stack, parameters.z);

        final Bounds layerBounds;
        if (parameters.bounds.isDefined()) {
            layerBounds = new Bounds(parameters.bounds.minX, parameters.bounds.minY, totalBounds.getMinZ(),
                                     parameters.bounds.maxX, parameters.bounds.maxY, totalBounds.getMaxZ());
        } else {
            layerBounds = totalBounds;
        }

        return String.format("%s/z/%s/box/%d,%d,%d,%d,%s/render-parameters",
                             stackUrlString, "%s",
                             layerBounds.getX(), layerBounds.getY(),
                             layerBounds.getWidth(), layerBounds.getHeight(),
                             parameters.scale);
    }

    void showSourceAndMask()
            throws IOException {

        final String layerUrlPattern = getLayerUrlPattern();

        final RenderLayerLoader layerLoader = new MaskedResinLayerLoader(layerUrlPattern,
                                                                         Collections.singletonList(parameters.z),
                                                                         ImageProcessorCache.DISABLED_CACHE,
                                                                         parameters.resinSigma,
                                                                         parameters.scale,
                                                                         parameters.resinContentThreshold,
                                                                         parameters.resinMaskIntensity);

        final LayerLoader.FloatProcessors floatProcessors = layerLoader.getProcessors(0);

        final ImagePlus source = new ImagePlus("source", floatProcessors.image);
        final ImagePlus mask = new ImagePlus("mask", floatProcessors.mask);

        source.show();
        mask.show();

        SimpleMultiThreading.threadHaltUnClean();
    }

    private static final Logger LOG = LoggerFactory.getLogger(MaskedResinDebugClient.class);
}
