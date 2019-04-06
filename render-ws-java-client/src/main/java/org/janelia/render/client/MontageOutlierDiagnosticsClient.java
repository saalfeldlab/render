package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.NoninvertibleModelException;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for loading outlier residual data from the Matlab montage diagnostics tool
 * and rendering the problem tile pairs with highlighted bounding boxes and match points
 * for seam review.
 *
 * @author Eric Trautman
 */
public class MontageOutlierDiagnosticsClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--outlierCsv",
                description = "Path to CSV file containing outlier pair data from diagnostics run",
                required = true)
        public String outlierCsv;

        @Parameter(
                names = "--onlyRenderMeanOutliers",
                description = "Indicates that only mean residual outlier pairs should be rendered",
                arity = 0)
        public boolean onlyRenderMeanOutliers;

        @Parameter(
                names = "--matchOwner",
                description = "Match collection owner (default is to use stack owner)")
        public String matchOwner;

        @Parameter(
                names = "--matchCollection",
                description = "Match collection name")
        public String matchCollection;

        @Parameter(
                names = "--rootOutputDirectory",
                description = "Root directory for diagnostic images")
        public String rootOutputDirectory = ".";

        @Parameter(
                names = "--z",
                description = "Explicit z values for pairs to be processed (if omitted, all pairs are processed)",
                variableArity = true) // e.g. --z 20.0 21.0 22.0
        public List<Double> zValues;

    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MontageOutlierDiagnosticsClient client = new MontageOutlierDiagnosticsClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final String stackName;
    private final RenderDataClient renderDataClient;
    private final RenderDataClient matchDataClient;

    private MontageOutlierDiagnosticsClient(final Parameters parameters) {

        this.parameters = parameters;
        this.stackName = parameters.stack;
        this.renderDataClient = parameters.renderWeb.getDataClient();

        if (parameters.matchCollection == null) {
            this.matchDataClient = null;
        } else {
            final String matchOwner =
                    parameters.matchOwner == null ? parameters.renderWeb.owner : parameters.matchOwner;
            this.matchDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                        matchOwner,
                                                        parameters.matchCollection);
        }
    }

    private boolean acceptPair(final String[] csValues) {
        boolean accept = true;
        if (parameters.onlyRenderMeanOutliers) {
            if (csValues.length > 6) {
                accept = "1".equals(csValues[6].trim());
            }
        }
        return accept;
    }

    private void run()
            throws Exception {

        final Map<Double, List<OutlierPair>> zToOutlierPairsMap = new HashMap<>();

        final Set<Double> zValues = parameters.zValues == null ? null : new HashSet<>(parameters.zValues);
        final Path csvPath = Paths.get(parameters.outlierCsv).toAbsolutePath();

        Files.lines(csvPath)
                .skip(1)
                .forEach(line -> {
                    // Z,Max PM Residual,Tile 1,Tile 2,PM X,PM Y,Mean Tile Pair Residual Is Outlier
                    final String[] v = line.split(",");
                    if (v.length > 3) {
                        final Double z = new Double(v[0]);
                        if ((zValues == null) || (zValues.contains(z))) {
                            final List<OutlierPair> pairList =
                                    zToOutlierPairsMap.computeIfAbsent(z,
                                                                       zValue -> new ArrayList<>());
                            if (acceptPair(v)) {
                                pairList.add(new OutlierPair(v[1], v[2], v[3], v[4], v[5]));
                            }
                        }
                    }
                });

        final int totalOutlierPairs = zToOutlierPairsMap.values().stream().mapToInt(List::size).sum();
        LOG.info("run: loaded {} outlier pairs from {}", totalOutlierPairs, csvPath);

        for (final Double z : zToOutlierPairsMap.keySet()) {
            final List<OutlierPair> pairList = zToOutlierPairsMap.get(z);
            if (pairList.size() > 0) {
                final File imageDir = FileUtil.createBatchedZDirectory(parameters.rootOutputDirectory,
                                                                       "problem_outlier_batch_",
                                                                       z);
                renderOutliersForZ(z, pairList, imageDir);
            }
        }

    }

    private void renderOutliersForZ(final Double z,
                                    final List<OutlierPair> pairList,
                                    final File imageDir)
            throws IOException {

        LOG.info("renderOutliersForZ: entry, rendering {} pairs for z {}", pairList.size(), z);

        final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(parameters.stack, z);
        final Map<String, TileBounds> tileIdToBoundsMap = new HashMap<>();
        tileBoundsList.forEach(tb -> tileIdToBoundsMap.put(tb.getTileId(), tb));

        pairList.forEach(pair -> {
            final TileBounds pBounds = tileIdToBoundsMap.get(pair.pTileId);
            final TileBounds qBounds = tileIdToBoundsMap.get(pair.qTileId);
            if (pBounds == null) {
                LOG.warn("skipping outlier with missing pTile: {}", pair);
            } else if (qBounds == null) {
                LOG.warn("skipping outlier with missing qTile: {}", pair);
            } else {
                pair.render(z, pBounds, qBounds, imageDir);
            }
        });

        if (pairList.size() > 0) {

            final String jsonFileName = String.format("problem_outlier_%s_z%06.0f.json", stackName, z);
            final Path path = Paths.get(imageDir.getAbsolutePath(), jsonFileName);

            // sort the pair list by problem name so that the json order matches problem image order on filesystem
            // note: problem name is set by render call above (hack-y)
            final StringBuilder outlierPairsJson = new StringBuilder();
            pairList.stream().sorted(Comparator.comparing(p -> p.problemName)).forEach(p -> {
                if (outlierPairsJson.length() > 0) {
                    outlierPairsJson.append(",\n");
                }
                outlierPairsJson.append(p.toJson());
            });

            final String json = "{\n  \"outlierPairs\": [\n" + outlierPairsJson + "\n  ]\n}";
            Files.write(path, json.getBytes());
        }

        LOG.info("renderOutliersForZ: exit");
    }

    private class OutlierPair {

        private final double maxResidualValue;
        private final String pTileId;
        private final String qTileId;
        private final double maxResidualX;
        private final double maxResidualY;
        private String problemName;

        OutlierPair(final String maxResidualValue,
                    final String pTileId,
                    final String qTileId,
                    final String maxResidualX,
                    final String maxResidualY) {
            this.maxResidualValue = Double.parseDouble(maxResidualValue);
            this.pTileId = pTileId;
            this.qTileId = qTileId;
            this.maxResidualX = Double.parseDouble(maxResidualX);
            this.maxResidualY = Double.parseDouble(maxResidualY);
            this.problemName = null;
        }

        @Override
        public String toString() {
            return "(" + pTileId + ", " + qTileId + ")";
        }

        public String toJson() {
            return "[\n  \"" + pTileId + "\",\n  \"" + qTileId + "\"\n]";
        }

        int scaleCoordinate(final double worldValue,
                            final double renderWorldMin,
                            final double scale) {
            return (int) ((worldValue - renderWorldMin) * scale);
        }

        int scaleSize(final double worldSize,
                      final double scale) {
            return (int) (worldSize * scale);
        }

        void drawTileBounds(final Graphics2D targetGraphics,
                            final RenderParameters renderParameters,
                            final TileBounds tileBounds,
                            final Color color) {

            targetGraphics.setStroke(new BasicStroke(2));
            targetGraphics.setColor(color);
            final int x = scaleCoordinate(tileBounds.getMinX(), renderParameters.getX(), renderParameters.getScale());
            final int y = scaleCoordinate(tileBounds.getMinY(), renderParameters.getY(), renderParameters.getScale());
            final int width = scaleSize(tileBounds.getDeltaX(), renderParameters.getScale());
            final int height = scaleSize(tileBounds.getDeltaY(), renderParameters.getScale());
            targetGraphics.drawRect(x, y, width, height);
        }

        void drawCircle(final Graphics2D targetGraphics,
                        final RenderParameters renderParameters,
                        final double fullScaleWorldX,
                        final double fullScaleWorldY,
                        final Color color,
                        final int size) {

            targetGraphics.setStroke(new BasicStroke(2));
            targetGraphics.setColor(color);
            final int x = (int) ((fullScaleWorldX - renderParameters.getX()) * renderParameters.getScale());
            final int y = (int) ((fullScaleWorldY - renderParameters.getY()) * renderParameters.getScale());
            targetGraphics.drawOval(x, y, size, size);
        }

        void render(final Double z,
                    final TileBounds pBounds,
                    final TileBounds qBounds,
                    final File imageDir) throws RuntimeException {

            final double scale;
            int circleSize = 20;
            if (maxResidualValue < 1500) {
                scale = 0.5;
                circleSize = 30;
            } else if (maxResidualValue < 15000) {
                scale = 0.2;
            } else if (maxResidualValue < 30000) {
                scale = 0.1;
            } else {
                scale = 0.05;
            }

            final Bounds pairBounds = pBounds.union(qBounds);

            final String urlString =
                    renderDataClient.getRenderParametersUrlString(parameters.stack,
                                                                  pairBounds.getMinX(),
                                                                  pairBounds.getMinY(),
                                                                  z,
                                                                  (int) pairBounds.getDeltaX(),
                                                                  (int) pairBounds.getDeltaY(),
                                                                  scale,
                                                                  null);

            RenderParameters renderParameters = RenderParameters.loadFromUrl(urlString);


            final Set<String> pairTileIds = new HashSet<>(Arrays.asList(pBounds.getTileId(), qBounds.getTileId()));

            final RenderParameters pairOnlyParameters =
                    new RenderParameters(null, renderParameters.x, renderParameters.y,
                                         renderParameters.width, renderParameters.height, scale);
            final Map<String, TileSpec> idToSpecMap = new HashMap<>();

            renderParameters.getTileSpecs().forEach(ts -> {
                if (pairTileIds.contains(ts.getTileId())) {
                    pairOnlyParameters.addTileSpec(ts);
                    idToSpecMap.put(ts.getTileId(), ts);
                }
            });

            renderParameters = pairOnlyParameters;


            final BufferedImage targetImage = renderParameters.openTargetImage();
            ArgbRenderer.render(renderParameters, targetImage, ImageProcessorCache.DISABLED_CACHE);

            final Graphics2D targetGraphics = targetImage.createGraphics();
            drawTileBounds(targetGraphics, renderParameters, pBounds, Color.GREEN);
            drawTileBounds(targetGraphics, renderParameters, qBounds, Color.RED);

            if (matchDataClient == null) {
                drawCircle(targetGraphics, renderParameters, maxResidualX, maxResidualY,
                           Color.CYAN, circleSize);
            } else {
                drawWorstMatch(idToSpecMap.get(pBounds.getTileId()),
                               idToSpecMap.get(qBounds.getTileId()),
                               renderParameters,
                               targetGraphics,
                               circleSize);
            }

            targetGraphics.dispose();

            this.problemName = String.format("problem_outlier_%s_z%06.0f_mr%04.0f_x%08d_y%08d_g%s_r%s",
                                             stackName, z, maxResidualValue,
                                             (int) renderParameters.getX(), (int) renderParameters.getY(),
                                             pBounds.getTileId(), qBounds.getTileId());

            final File problemImageFile = new File(imageDir, problemName + ".jpg").getAbsoluteFile();

            try {
                Utils.saveImage(targetImage, problemImageFile, false, 0.85f);
            } catch (final IOException e) {
                throw new RuntimeException("wrapped exception", e);
            }
        }

        private void drawWorstMatch(final TileSpec pSpec,
                                    final TileSpec qSpec,
                                    final RenderParameters renderParameters,
                                    final Graphics2D targetGraphics,
                                    final int circleSize) {
            try {

                final TileSpec pNormalizedSpec = getNormalizedTileSpec(pSpec);
                final TileSpec qNormalizedSpec = getNormalizedTileSpec(qSpec);

                final CanvasMatches canvasMatches =
                        matchDataClient.getMatchesBetweenTiles(pSpec.getSectionId(), pSpec.getTileId(),
                                                               qSpec.getSectionId(), qSpec.getTileId());

                final Matches matches = canvasMatches.getMatches();

                final double[][] ps;
                final double[][] qs;
                if (canvasMatches.getpId().equals(pSpec.getTileId())) {
                    ps = matches.getPs();
                    qs = matches.getQs();
                } else {
                    ps = matches.getQs();
                    qs = matches.getPs();
                }

                Point2D.Double pWorst = null;
                Point2D.Double qWorst = null;
                double largestDistance = Double.MIN_VALUE;
                for (int i = 0; i < ps.length; i++) {
                    final Point2D.Double pWorld = getPoint(pNormalizedSpec, ps[0][i], ps[1][i], pSpec);
                    final Point2D.Double qWorld = getPoint(qNormalizedSpec, qs[0][i], qs[1][i], qSpec);
                    final double distance = pWorld.distance(qWorld);
                    if (distance > largestDistance) {
                        largestDistance = distance;
                        pWorst = pWorld;
                        qWorst = qWorld;
                    }
                }

                if (pWorst != null) {
                    drawCircle(targetGraphics, renderParameters, pWorst.x, pWorst.y, Color.GREEN, circleSize);
                    drawCircle(targetGraphics, renderParameters, qWorst.x, qWorst.y, Color.RED, circleSize);
                    LOG.info(String.format("drawWorstMatch: worst match distance is %1.0f, max residual is %1.0f",
                                           largestDistance, maxResidualValue));
                }

            } catch (final Exception e) {
                LOG.error("drawWorstMatch: failed for " + pSpec.getTileId() + " and " + qSpec.getTileId(), e);
            }
        }

        private Point2D.Double getPoint(final TileSpec normalizedTileSpec,
                                        final double matchX,
                                        final double matchY,
                                        final TileSpec tileSpec)
                throws NoninvertibleModelException {
            final double[] local = normalizedTileSpec.getLocalCoordinates(matchX, matchY,
                                                                          normalizedTileSpec.getMeshCellSize());
            final double[] world = tileSpec.getWorldCoordinates(local[0], local[1]);
            return new Point2D.Double(world[0], world[1]);
        }

        private TileSpec getNormalizedTileSpec(final TileSpec tileSpec) {
            final String url = renderDataClient.getUrls().getTileUrlString(parameters.stack, tileSpec.getTileId()) +
                               "/render-parameters?normalizeForMatching=true";
            final RenderParameters normalizedParameters = RenderParameters.loadFromUrl(url);
            //noinspection OptionalGetWithoutIsPresent
            return normalizedParameters.getTileSpecs().stream().findFirst().get();
        }
    }

     private static final Logger LOG = LoggerFactory.getLogger(MontageOutlierDiagnosticsClient.class);
}
