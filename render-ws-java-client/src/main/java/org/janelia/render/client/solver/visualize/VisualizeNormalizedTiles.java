package org.janelia.render.client.solver.visualize;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImageJ;
import ij.ImagePlus;

import java.util.Collections;
import java.util.List;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for visualizing one or more matching normalized render tiles in ImageJ.
 */
public class VisualizeNormalizedTiles {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(names = "--tileId",
                description = "tile identifier",
                required = true,
                variableArity = true)
        public List<String> tileIds;

        @Parameter(names = "--renderScale", description = "Scale to render tiles and matches")
        public Double renderScale = 1.0;

        @Parameter(names = "--renderWithFilter", description = "Render tiles with filter")
        public boolean renderWithFilter = false;

        @Parameter(names = "--hackDataString", description = "Hack: override last transform data string with this one")
        public String hackDataString;

        public Parameters() {
        }
    }

    public static void main(String[] args) {

        if (args.length == 0) {
            //           {
            //            "type" : "leaf",
            //            "id" : "FIBSEM_correct",
            //            "className" : "org.janelia.alignment.transform.SEMDistortionTransformA",
            //            "dataString" : "19.4 64.8 24.4 972.0 0"
            //          }
            args = new String[] {
                    "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                    "--owner", "reiser",
                    "--project", "Z0422_05_Ocellar",
                    "--stack", "v3_acquire",
                    "--tileId", "22-06-17_080526_0-0-1.1263.0",
                    //"--tileId", "22-06-17_081143_0-0-1.1264.0",
                    "--hackDataString", "1900.4 64.8 24.4 972.0 0"
            };
        }

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final VisualizeNormalizedTiles client = new VisualizeNormalizedTiles(parameters);
                client.go();
            }
        };

        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final ImageProcessorCache imageProcessorCache;

    VisualizeNormalizedTiles(final Parameters parameters)
            throws IllegalArgumentException {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        // using cache helps a little with loading large masks over VPN
        this.imageProcessorCache =
                new ImageProcessorCache(4 * 15000 * 10000, // 4 big images
                                        true,
                                        false);
    }

    private void go() {

        new ImageJ();

        for (final String tileId : parameters.tileIds) {

            final String tileUrl = renderDataClient.getUrls().getTileUrlString(parameters.stack, tileId) +
                                   "/render-parameters?normalizeForMatching=true&filter=" +
                                   parameters.renderWithFilter + "&scale=" + parameters.renderScale;

            final RenderParameters renderParameters = RenderParameters.loadFromUrl(tileUrl);
            renderParameters.initializeDerivedValues();

            if (parameters.hackDataString != null) {
                final TileSpec tileSpec = renderParameters.getTileSpecs().get(0);
                final LeafTransformSpec lastTransformSpec = (LeafTransformSpec) tileSpec.getLastTransform();
                final LeafTransformSpec hackedTransformSpec = new LeafTransformSpec(lastTransformSpec.getClassName(),
                                                                                    parameters.hackDataString);
                tileSpec.removeLastTransformSpec();
                tileSpec.addTransformSpecs(Collections.singletonList(hackedTransformSpec));
                tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

            }

            if (LOG.isInfoEnabled()) {
                try {
                    LOG.info("tile {} render parameters are:\n{}", tileId, renderParameters.toJson());
                } catch (final Exception e) {
                    LOG.error("failed to log render parameters for tile " + tileId, e);
                }
            }

            final TransformMeshMappingWithMasks.ImageProcessorWithMasks
                    ipwm = Renderer.renderImageProcessorWithMasks(renderParameters, imageProcessorCache);

            new ImagePlus(tileId, ipwm.ip).show();
        }

        LOG.info("visualization is complete: use File->Save as needed, kill process when done viewing");

        SimpleMultiThreading.threadHaltUnClean();
    }

    private static final Logger LOG = LoggerFactory.getLogger(VisualizeNormalizedTiles.class);
}
