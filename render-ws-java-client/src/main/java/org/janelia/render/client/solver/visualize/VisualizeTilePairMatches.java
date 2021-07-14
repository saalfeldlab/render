package org.janelia.render.client.solver.visualize;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImageJ;
import ij.ImagePlus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageDebugUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for visualizing matches for a tile pair.
 */
public class VisualizeTilePairMatches {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();
        @Parameter(names = "--stack", description = "Stack name", required = true) public String stack;
        @Parameter(names = "--pTileId", description = "P tile identifier", required = true) public String pTileId;
        @Parameter(names = "--qTileId", description = "Q tile identifier", required = true) public String qTileId;
        @Parameter(names = "--collection", description = "Match collection name", required = true) public String collection;
        @Parameter(names = "--renderScale", description = "Scale to render tiles and matches") public Double renderScale = 1.0;
        public Parameters() {
        }
    }

    public static void main(String[] args) {

        if (args.length == 0) {
            args = new String[] {
                    "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                    "--owner", "Z0720_07m_BR",
                    "--project", "Sec24",
                    "--stack", "v3_acquire_trimmed",
                    "--collection", "Sec24_wobble_fix_3", // "Sec24_v1", "Sec24_wobble_fix_1"
                    "--pTileId", "21-04-29_151034_0-0-0.57325.0",
                    "--qTileId", "21-04-29_151547_0-0-0.57326.0",
                    "--scale", "0.1",
            };
        }

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final VisualizeTilePairMatches client = new VisualizeTilePairMatches(parameters);
                client.showMatches();
            }
        };

        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;
    private final ImageProcessorCache imageProcessorCache;

    VisualizeTilePairMatches(final Parameters parameters)
            throws IllegalArgumentException {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
        this.matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                    parameters.renderWeb.owner,
                                                    parameters.collection);
        // using cache helps a little with loading large masks over VPN
        this.imageProcessorCache =
                new ImageProcessorCache(4 * 15000 * 10000, // 4 big images
                                        true,
                                        false);
    }

    void showMatches()
            throws IOException {

        new ImageJ();

        final DebugTile pTile = new DebugTile(parameters.pTileId);
        final DebugTile qTile = new DebugTile(parameters.qTileId);

        final ImagePlus pIp = pTile.render();
        final ImagePlus qIp = qTile.render();

        final CanvasMatches canvasMatches = matchDataClient.getMatchesBetweenTiles(pTile.getGroupId(), pTile.getId(),
                                                                                   qTile.getGroupId(), qTile.getId());

        final List<PointMatch> pointMatchList =
                CanvasMatchResult.convertMatchesToPointMatchList(canvasMatches.getMatches());
        final List<Point> pPointList = new ArrayList<>(pointMatchList.size());
        final List<Point> qPointList = new ArrayList<>(pointMatchList.size());
        pointMatchList.forEach(pm -> {
            final Point pPoint = pm.getP1();
            final Point qPoint = pm.getP2();
            if (parameters.renderScale != 1.0) {
                scaleLocal(pPoint);
                scaleLocal(qPoint);
            }
            pPointList.add(pPoint);
            qPointList.add(qPoint);
        });

        ImageDebugUtil.setPointRois(pPointList, pIp);
        ImageDebugUtil.setPointRois(qPointList, qIp);

        pIp.show();
        qIp.show();
        
        SimpleMultiThreading.threadHaltUnClean();
    }

    private void scaleLocal(final Point point) {
        final double[] pLocal = point.getL();
        for (int i = 0; i < pLocal.length; i++) {
            pLocal[i] = pLocal[i] * parameters.renderScale;
        }
    }

    private class DebugTile {

        final RenderParameters renderParameters;
        final TileSpec tileSpec;

        public DebugTile(final String tileId)
                throws IOException {
            this.renderParameters = renderDataClient.getRenderParametersForTile(parameters.stack, tileId);
            this.renderParameters.setScale(parameters.renderScale);
            this.renderParameters.initializeDerivedValues();
            this.tileSpec = renderParameters.getTileSpecs().get(0);
        }

        public String getGroupId() {
            return tileSpec.getLayout().getSectionId();
        }

        public String getId() {
            return tileSpec.getTileId();
        }

        public ImagePlus render() {
            final TransformMeshMappingWithMasks.ImageProcessorWithMasks
                    ipwm = Renderer.renderImageProcessorWithMasks(renderParameters, imageProcessorCache);
            return new ImagePlus(getId(), ipwm.ip);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(VisualizeTilePairMatches.class);
}
