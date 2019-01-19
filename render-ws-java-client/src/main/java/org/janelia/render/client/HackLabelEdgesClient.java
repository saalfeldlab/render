package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.betterbox.BoxData;
import org.janelia.alignment.mipmap.BoxMipmapGenerator;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.LabelImageProcessorCache;
import org.janelia.render.client.betterbox.LabelBoxValidator;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import au.com.bytecode.opencsv.CSVReader;

/**
 * Java client for rendering tile labels with separate labelling of specified edge pixels.
 * This is a hack to improve DMG intensity correction for bad source tile edges.
 *
 * @author Eric Trautman
 */
public class HackLabelEdgesClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--rootDirectory",
                description = "Root directory for rendered labels (e.g. /nrs/saalfeld/rendered_boxes)",
                required = true)
        public String rootDirectory;

        @Parameter(
                names = "--replaceExistingLabel",
                description = "Indicates that an existing labels box ([rootDirectory]/[width]x[height]/0/[z]/[row]/[col].[format]) " +
                              "should be replaced with the hacked labels box.  " +
                              "If false, the hacked labels box will simply be saved to rootDirectory.",
                arity = 1)
        public Boolean replaceExistingLabel = true;

        @Parameter(
                names = "--width",
                description = "Width of labels boxes")
        public Integer width = 8192;

        @Parameter(
                names = "--height",
                description = "Height of labels boxes")
        public Integer height = 8192;

        @Parameter(
                names = "--edgePixels",
                description = "Number of pixels to include in edge label (may need to be larger for highly warped tiles")
        public Integer edgePixels = 2;

        @Parameter(
                names = "--format",
                description = "Format for rendered labels box"
        )
        public String format = Utils.PNG_FORMAT;

        @Parameter(
                names = "--edgeCsvFile",
                description = "Comma separated value file containing data for tile edges to hack.  " +
                              "Each line in the file should have the format [z],[tileId],[position] " +
                              "(e.g. '2408,150504171631052111.2408.0,TOP').  " +
                              "NOTE: If an edge is included in the file, ALL hack edges for that layer should be included.",
                required = true)
        public String edgeCsvFile;

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

                final HackLabelEdgesClient client = new HackLabelEdgesClient(parameters);
                client.generateHackedLabels(true);
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    HackLabelEdgesClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    void generateHackedLabels(final boolean checkTileSizes)
            throws IOException {
        final Map<Double, List<TileEdge>> zToEdgeList = parseTileEdgeCsvFile(parameters.edgeCsvFile);
        for (final Double z : zToEdgeList.keySet().stream().sorted().collect(Collectors.toList())) {
            generateHackedLabelsForZ(checkTileSizes, z, zToEdgeList.get(z));
        }
    }

    private void generateHackedLabelsForZ(final boolean checkTileSizes,
                                          final Double z,
                                          final List<TileEdge> tileEdgeList)
            throws IllegalArgumentException, IOException {


        final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
        final ResolvedTileSpecCollection resolvedTiles = dataClient.getResolvedTiles(parameters.stack, z);

        final Map<Integer, Set<Integer>> rowToColumnSet = new HashMap<>();
        for (final TileEdge tileEdge : tileEdgeList) {

            final TileSpec tileSpec = resolvedTiles.getTileSpec(tileEdge.tileId);
            if (tileSpec == null) {
                throw new IllegalArgumentException("tile edge " + tileEdge +  " not found in layer " + z);
            }

            if (checkTileSizes &&
                ((tileSpec.getHeight() > parameters.height) || (tileSpec.getWidth() > parameters.width))) {
                throw new IllegalArgumentException(
                        "cases where tile spec size is greater that label size are not supported");
            }

            addRowAndColumn(tileSpec.getMinX(), tileSpec.getMinY(), rowToColumnSet);
            addRowAndColumn(tileSpec.getMinX(), tileSpec.getMaxY(), rowToColumnSet);
            addRowAndColumn(tileSpec.getMaxX(), tileSpec.getMinY(), rowToColumnSet);
            addRowAndColumn(tileSpec.getMaxX(), tileSpec.getMaxY(), rowToColumnSet);
        }

        final LabelWithEdgesImageProcessorCache hackedCache =
                new LabelWithEdgesImageProcessorCache(0,
                                                      false,
                                                      false,
                                                      resolvedTiles.getTileSpecs(),
                                                      parameters.edgePixels,
                                                      tileEdgeList);

        final List<Integer> rowList = rowToColumnSet.keySet().stream().sorted().collect(Collectors.toList());
        for (final Integer row : rowList) {
            final List<Integer> columnList = rowToColumnSet.get(row).stream().sorted().collect(Collectors.toList());
            for (final Integer column : columnList) {
                generateHackedLabelBox(dataClient, hackedCache, z, row, column);
            }
        }

    }

    private void generateHackedLabelBox(final RenderDataClient dataClient,
                                        final LabelWithEdgesImageProcessorCache hackedCache,
                                        final double z,
                                        final int row,
                                        final int column)
            throws IOException {
        final double x = column * parameters.width;
        final double y = row * parameters.height;
        final double scale = 1.0;

        final String renderUrl = dataClient.getRenderParametersUrlString(parameters.stack,
                                                                         x, y, z,
                                                                         parameters.width, parameters.height,
                                                                         scale, null);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderUrl);

        // override intensity range for 16-bit labels
        renderParameters.setMinIntensity(0.0);
        renderParameters.setMaxIntensity(65535.0);

        // skip interpolation to make sure warped edge color remains constant
        renderParameters.setSkipInterpolation(true);

        // make sure labels always use binary mask
        renderParameters.setBinaryMask(true);


        final BufferedImage targetImage = BoxMipmapGenerator.renderBoxImage(renderParameters, hackedCache);

        final File imageFile;
        File backupFile = null;
        Set<Integer> priorNonEmptyLabelColors = null;

        if (parameters.replaceExistingLabel) {

            final String boxName = parameters.width + "x" + parameters.height + "-label";
            final Path boxPath = Paths.get(parameters.rootDirectory,
                                           parameters.renderWeb.project,
                                           parameters.stack,
                                           boxName).toAbsolutePath();

            final File boxDirectory = boxPath.toFile().getAbsoluteFile();
            final String baseBoxPath = boxDirectory.getPath();
            final String boxPathSuffix = "." + parameters.format.toLowerCase();

            final BoxData boxData = new BoxData(z, 0, row, column);
            imageFile = boxData.getAbsoluteLevelFile(baseBoxPath, boxPathSuffix);

            if (! imageFile.exists()) {
                throw new IOException("original labels box " + imageFile.getAbsolutePath() + " does not exist");
            }

            final String backupName = imageFile.getName() + ".hacked_" + new Date().getTime();
            backupFile = new File(imageFile.getParent(), backupName);
            Files.move(imageFile.toPath(), backupFile.toPath());

            LOG.info("generateHackedLabelBox: moved {} to {}", imageFile.getAbsolutePath(), backupFile.getAbsolutePath());

            priorNonEmptyLabelColors = LabelBoxValidator.getNonEmptyLabelColors(backupFile.getAbsolutePath());

        } else {

            final String imageName = String.format("label_row_%04d_col_%04d_%dx%d_z_%1.0f.%s",
                                                   row, column,
                                                   parameters.width, parameters.height,
                                                   z, parameters.format);

            imageFile = new File(parameters.rootDirectory, imageName);
        }

        BoxMipmapGenerator.saveImage(targetImage, imageFile, true, parameters.format);

        if (priorNonEmptyLabelColors != null) {
            final Set<Integer> hackedNonEmptyLabelColors =
                    LabelBoxValidator.getNonEmptyLabelColors(imageFile.getAbsolutePath());
            if (priorNonEmptyLabelColors.size() != hackedNonEmptyLabelColors.size()) {
                LOG.info("generateHackedLabelBox: non-empty label color counts changed from {} to {}",
                         priorNonEmptyLabelColors.size(), hackedNonEmptyLabelColors.size());
            } else {
                LOG.warn("generateHackedLabelBox: hack did NOT change non-empty label color counts from {}, restoring {}",
                         priorNonEmptyLabelColors.size(), backupFile.getAbsolutePath());
                Files.move(backupFile.toPath(), imageFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Map<Double, List<TileEdge>> parseTileEdgeCsvFile(final String csvFilePath)
            throws IOException {

        LOG.info("parseTileEdgeCsvFile: parsing {}", csvFilePath);

        int edgeCount = 0;

        final Map<Double, List<TileEdge>> zToEdgeList = new HashMap<>();
        try (final CSVReader csvReader = new CSVReader(new FileReader(csvFilePath))) {
            String[] values;
            while ((values = csvReader.readNext()) != null) {
                if (values.length == 3) {
                    final String zString = values[0].trim();
                    final String positionString = values[2].trim().toUpperCase();
                    if ((zString.length() > 0) && (Character.isDigit(zString.charAt(0)))) {
                        final Double z = Double.parseDouble(zString);
                        final List<TileEdge> tileEdgeList = zToEdgeList.computeIfAbsent(z, list -> new ArrayList<>());
                        final TileEdgePosition position;
                        try {
                            position = TileEdgePosition.valueOf(positionString);
                        } catch (final Throwable t) {
                            throw new IllegalArgumentException("failed to parse tile edge position '" + positionString + "'", t);
                        }
                        tileEdgeList.add(new TileEdge(values[1], position));
                        edgeCount++;
                    }
                }
            }
        }

        LOG.info("parseTileEdgeCsvFile: loaded {} edges from {}", edgeCount, csvFilePath);

        return zToEdgeList;
    }

    private void addRowAndColumn(final double x,
                                 final double y,
                                 final Map<Integer, Set<Integer>> rowToColumnSet) {
        final int row = (int) (y / parameters.height);
        final int column = (int) (x / parameters.width);
        final Set<Integer> columnSet = rowToColumnSet.computeIfAbsent(row, cList -> new HashSet<>());
        columnSet.add(column);
    }

    private enum TileEdgePosition {
        TOP, RIGHT, BOTTOM, LEFT
    }

    private static class TileEdge {

        private final String tileId;
        private final TileEdgePosition position;

        private TileEdge(final String tileId,
                         final TileEdgePosition position) {
            this.tileId = tileId;
            this.position = position;
        }

        @Override
        public String toString() {
            return tileId + "::" + position;
        }

    }

    private static class TileEdgeAndColor {

        private final TileEdge tileEdge;
        private final Color color;

        private TileEdgeAndColor(final TileEdge tileEdge,
                                 final Color color) {
            this.tileEdge = tileEdge;
            this.color = color;
        }
    }

    private class LabelWithEdgesImageProcessorCache
            extends LabelImageProcessorCache {

        private final int edgePixelSize;
        private final Map<String, List<TileEdgeAndColor>>
                urlToEdgeAndColorList;

        LabelWithEdgesImageProcessorCache(final long maximumNumberOfCachedPixels,
                                          final boolean recordStats,
                                          final boolean cacheOriginalsForDownSampledImages,
                                          final Collection<TileSpec> tileSpecs,
                                          final int edgePixelSize,
                                          final List<TileEdge> tileEdgeList) {

            super(maximumNumberOfCachedPixels, recordStats, cacheOriginalsForDownSampledImages, tileSpecs);

            this.edgePixelSize = edgePixelSize;
            this.urlToEdgeAndColorList = new HashMap<>();

            buildEdgeMap(tileSpecs, tileEdgeList);
        }

        @Override
        protected ImageProcessor loadImageProcessor(final String url,
                                                    final int downSampleLevels,
                                                    final boolean isMask,
                                                    final boolean convertTo16Bit)
                throws IllegalArgumentException {

            final ImageProcessor imageProcessor =
                    super.loadImageProcessor(url, downSampleLevels, isMask, convertTo16Bit);

            if (! isMask) {

                final ShortProcessor shortProcessor = (ShortProcessor) imageProcessor;

                final List<TileEdgeAndColor> tileEdgeAndColorList = urlToEdgeAndColorList.get(url);

                if (tileEdgeAndColorList != null) {

                    for (final TileEdgeAndColor tileEdgeAndColor : tileEdgeAndColorList) {

                        int minX = 0;
                        int maxX = shortProcessor.getWidth();
                        int minY = 0;
                        int maxY = shortProcessor.getHeight();

                        switch (tileEdgeAndColor.tileEdge.position) {
                            case TOP:
                                maxY = minY + edgePixelSize;
                                break;
                            case RIGHT:
                                minX = maxX - edgePixelSize;
                                break;
                            case BOTTOM:
                                minY = maxY - edgePixelSize;
                                break;
                            case LEFT:
                                maxX = minX + edgePixelSize;
                                break;
                        }

                        final short edgeRGB = (short) tileEdgeAndColor.color.getRGB();
                        for (int y = minY; y < maxY; y++) {
                            for (int x = minX; x < maxX; x++) {
                                shortProcessor.set(x, y, edgeRGB);
                            }
                        }

                        if (LOG.isDebugEnabled()) {
                            LOG.debug("loadImageProcessor: set {} {} pixel edge of tile {} to color {}",
                                      tileEdgeAndColor.tileEdge.position,
                                      edgePixelSize,
                                      tileEdgeAndColor.tileEdge.tileId,
                                      shortProcessor.get(minX, minY));
                        }
                    }

                }

            }

            return imageProcessor;
        }

        private void buildEdgeMap(final Collection<TileSpec> tileSpecs,
                                  final List<TileEdge> tileEdgeList) {

            final List<Color> colorList = buildColorList();

            final int requiredNumberOfDistinctLabels = tileSpecs.size() + tileEdgeList.size();
            if (requiredNumberOfDistinctLabels  > colorList.size()) {
                throw new IllegalArgumentException(
                        tileSpecs.size() + " tile specs and " + tileEdgeList.size() +
                        " edges were specified but color model can only support a maximum of " +
                        colorList.size() + " distinct labels");
            }

            int colorIndex = tileSpecs.size();
            for (final TileEdge tileEdge : tileEdgeList) {
                final TileEdgeAndColor tileEdgeAndColor = new TileEdgeAndColor(tileEdge, colorList.get(colorIndex));
                // assuming inefficient loop through tile specs is okay since there should not be too many edges
                for (final TileSpec tileSpec : tileSpecs) {
                    if (tileEdge.tileId.equals(tileSpec.getTileId())) {
                        final ChannelSpec firstChannelSpec = tileSpec.getAllChannels().get(0);
                        final String imageUrl = firstChannelSpec.getFloorMipmapEntry(0).getValue().getImageUrl();
                        final List<TileEdgeAndColor> edgeAndColorList =
                                urlToEdgeAndColorList.computeIfAbsent(imageUrl, s -> new ArrayList<>());
                        edgeAndColorList.add(tileEdgeAndColor);
                        break;
                    }
                }
                colorIndex++;
            }

        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(HackLabelEdgesClient.class);
}
