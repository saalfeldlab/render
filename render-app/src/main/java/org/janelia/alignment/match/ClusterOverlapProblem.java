package org.janelia.alignment.match;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Information about overlapping same layer tile clusters that can be rendered for review.
 *
 * @author Eric Trautman
 */
public class ClusterOverlapProblem {

    public static List<ClusterOverlapProblem> findOverlapProblems(final Double originalZ,
                                                                  final List<List<TileBounds>> clusterBoundsLists) {
        
        final List<ClusterOverlapProblem> overlapProblems = new ArrayList<>();

        final List<TileBoundsRTree> clusterBoundsTrees = new ArrayList<>(clusterBoundsLists.size());
        for (int i = 0; i < clusterBoundsLists.size(); i++) {
            clusterBoundsTrees.add(new TileBoundsRTree((double) i, clusterBoundsLists.get(i)));
        }

        ClusterOverlapProblem overlapProblem;
        for (int clusterIndex = 0; clusterIndex < clusterBoundsLists.size(); clusterIndex++) {

            final TileBoundsRTree boundsRTree = clusterBoundsTrees.get(clusterIndex);

            for (int otherClusterIndex = clusterIndex + 1;
                 otherClusterIndex < clusterBoundsTrees.size();
                 otherClusterIndex++) {

                overlapProblem = null;

                for (final TileBounds tileBounds : clusterBoundsLists.get(otherClusterIndex)) {

                    final List<TileBounds> intersectingTiles = boundsRTree.findTilesInBox(tileBounds.getMinX(),
                                                                                          tileBounds.getMinY(),
                                                                                          tileBounds.getMaxX(),
                                                                                          tileBounds.getMaxY());
                    if (intersectingTiles.size() > 0) {
                        if (overlapProblem == null) {
                            overlapProblem = new ClusterOverlapProblem(originalZ,
                                                                       tileBounds,
                                                                       intersectingTiles);
                        } else {
                            overlapProblem.addProblem(tileBounds, intersectingTiles);
                        }
                    }

                }

                if (overlapProblem != null) {
                    overlapProblems.add(overlapProblem);
                }
            }

        }

        return overlapProblems;
    }

    public static RenderParameters getScaledRenderParametersForBounds(final RenderWebServiceUrls renderWebServiceUrls,
                                                                      final String stackName,
                                                                      final Double z,
                                                                      final Bounds bounds,
                                                                      final int maxHeightOrWidth) {

        final int maxHeight = Math.min(maxHeightOrWidth, (int) bounds.getDeltaY());
        final int maxWidth = Math.min(maxHeightOrWidth, (int) bounds.getDeltaX());

        final double scaleX = maxWidth / bounds.getDeltaX();
        final double scaleY = maxHeight / bounds.getDeltaY();
        final double scale = Math.min(scaleX, scaleY);

        final String urlString = renderWebServiceUrls.getRenderParametersUrlString(stackName,
                                                                                   bounds.getMinX(),
                                                                                   bounds.getMinY(),
                                                                                   z,
                                                                                   (int) bounds.getDeltaX(),
                                                                                   (int) bounds.getDeltaY(),
                                                                                   scale,
                                                                                   null);
        return RenderParameters.loadFromUrl(urlString);
    }

    public static void drawClusterBounds(final BufferedImage targetImage,
                                         final RenderParameters renderParameters,
                                         final Collection<TileBounds> clusterTileBounds,
                                         final Color color) {
        final Graphics2D targetGraphics = targetImage.createGraphics();
        targetGraphics.setFont(ROW_COLUMN_FONT);
        clusterTileBounds.forEach(tb -> drawTileBounds(targetGraphics, renderParameters, tb, color));
        targetGraphics.dispose();
    }

    public static String getTileIdListJson(final Collection<String> tileIds) {
        final StringBuilder json = new StringBuilder();
        tileIds.stream().sorted().forEach(tileId -> {
            if (json.length() > 0) {
                json.append(",\n");
            }
            json.append('"').append(tileId).append('"');
        });
        return "[\n" + json + "\n]";
    }

    private final Double originalZ;
    private final Double z;
    private final Map<String, TileBounds> tileIdToBounds;
    private final Double intersectingZ;
    private final Map<String, TileBounds> intersectingTileIdToBounds;
    private final List<String> problemDetailsList;
    private String problemName;
    private File problemImageFile;

    private ClusterOverlapProblem(final Double originalZ,
                                  final TileBounds tileBounds,
                                  final List<TileBounds> intersectingTileBoundsList) {
        this.originalZ = originalZ;
        this.z = tileBounds.getZ();
        this.tileIdToBounds = new HashMap<>();
        this.intersectingZ = intersectingTileBoundsList.get(0).getZ();
        this.intersectingTileIdToBounds = new HashMap<>();
        this.problemDetailsList = new ArrayList<>();
        this.problemName = null;
        this.problemImageFile = null;
        addProblem(tileBounds, intersectingTileBoundsList);
    }

    private void addProblem(final TileBounds tileBounds,
                            final List<TileBounds> intersectingTileBoundsList)  {

        this.tileIdToBounds.put(tileBounds.getTileId(), tileBounds);

        final List<String> intersectingTileIds = new ArrayList<>();
        intersectingTileBoundsList.forEach(tb -> {
            intersectingTileIdToBounds.put(tb.getTileId(), tb);
            intersectingTileIds.add(tb.getTileId());
        });

        final String details = "cluster overlap: z " + z + " tile " + tileBounds.getTileId() +
                               " overlaps z " + intersectingZ + " tile(s) " + intersectingTileIds;

        this.problemDetailsList.add(details);
    }

    public void logProblemDetails() {
        problemDetailsList.forEach(LOG::warn);
        if (problemImageFile != null) {
            LOG.warn("cluster overlap image saved to {}\n", problemImageFile);
        }
    }

    public void render(final RenderWebServiceUrls renderWebServiceUrls,
                       final String stackName,
                       final File toDirectory) {

        final List<Bounds> allBounds = new ArrayList<>(intersectingTileIdToBounds.values());
        allBounds.addAll(tileIdToBounds.values());

        @SuppressWarnings("OptionalGetWithoutIsPresent")
        final Bounds problemBounds = allBounds.stream().reduce(Bounds::union).get();

        final RenderParameters parameters = getParameters(renderWebServiceUrls, stackName, problemBounds);
        final RenderParameters otherParameters = getParameters(renderWebServiceUrls, stackName, problemBounds);

        for (final TileSpec tileSpec : otherParameters.getTileSpecs()) {
            parameters.addTileSpec(tileSpec);
        }

        final BufferedImage targetImage = parameters.openTargetImage();
        ArgbRenderer.render(parameters, targetImage, ImageProcessorCache.DISABLED_CACHE);
        drawClusterBounds(targetImage, parameters, intersectingTileIdToBounds.values(), Color.GREEN);
        drawClusterBounds(targetImage, parameters, tileIdToBounds.values(), Color.RED);

        this.problemName = String.format("problem_overlap_%s_z%1.0f_gz%1.0f_rz%1.0f_x%d_y%d",
                                         stackName, originalZ, intersectingZ, z,
                                         (int) parameters.getX(), (int) parameters.getY());

        this.problemImageFile = new File(toDirectory, problemName + ".jpg").getAbsoluteFile();

        try {
            Utils.saveImage(targetImage, this.problemImageFile, false, 0.85f);
        } catch (final IOException e) {
            LOG.error("failed to save data for " + problemName, e);
        }
    }

    public String toJson() {
        final String greenIdJson = getTileIdListJson(intersectingTileIdToBounds.keySet());
        final String redIdJson = getTileIdListJson(tileIdToBounds.keySet());
        return "{\n" +
               "  \"problemName\":  \"" + problemName + "\",\n" +
               "  \"greenTileIds\": " + greenIdJson + ",\n" +
               "  \"redTileIds\":   " + redIdJson + "\n" +
               "}";
    }

    public ClusterOverlapBounds getBounds() {
        return new ClusterOverlapBounds(z,
                                        intersectingTileIdToBounds.values(),
                                        tileIdToBounds.values());
    }

    private RenderParameters getParameters(final RenderWebServiceUrls renderWebServiceUrls,
                                           final String stackName,
                                           final Bounds intersectingBounds) {

        return getScaledRenderParametersForBounds(renderWebServiceUrls,
                                                  stackName,
                                                  originalZ,
                                                  intersectingBounds,
                                                  800);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ClusterOverlapProblem.class);

    private static final Font ROW_COLUMN_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 16);

    private static void drawTileBounds(final Graphics2D targetGraphics,
                                       final RenderParameters renderParameters,
                                       final TileBounds tileBounds,
                                       final Color color) {
        targetGraphics.setStroke(new BasicStroke(2));
        targetGraphics.setColor(color);
        final int x = (int) ((tileBounds.getMinX() - renderParameters.getX()) * renderParameters.getScale());
        final int y = (int) ((tileBounds.getMinY() - renderParameters.getY()) * renderParameters.getScale());
        final int width = (int) (tileBounds.getDeltaX() * renderParameters.getScale());
        final int height = (int) (tileBounds.getDeltaY() * renderParameters.getScale());

        // HACK: draw row and column label for FAFB style tileIds
        final String tileId = tileBounds.getTileId();
        final int firstDot = tileId.indexOf('.');
        if (firstDot > 6) {
            final String rowAndColumn = tileId.substring(firstDot - 6, firstDot);
            targetGraphics.drawString(rowAndColumn, (x + 10), (y + 20));
        }
        targetGraphics.drawRect(x, y, width, height);
    }


}
