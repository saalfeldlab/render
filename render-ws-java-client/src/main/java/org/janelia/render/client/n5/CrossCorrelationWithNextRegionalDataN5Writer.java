package org.janelia.render.client.n5;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.NeuroglancerAttributes;
import org.janelia.render.client.zspacing.CrossCorrelationWithNextRegionalData;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Cursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.type.numeric.real.FloatType;

import static org.janelia.alignment.util.NeuroglancerUtil.buildPositionAndScales;

public class CrossCorrelationWithNextRegionalDataN5Writer
        implements Serializable {

    public static void writeN5(final List<CrossCorrelationWithNextRegionalData> dataList,
                               final String basePath,
                               final String datasetName,
                               final StackMetaData stackMetaData,
                               final String stackResolutionUnit) {

        final int numberOfLayers = dataList.size();
        LOG.info("writeN5: entry, processing {} z layers for -i {} -d {}",
                 numberOfLayers, basePath, datasetName);

        final CrossCorrelationWithNextRegionalData firstLayerData = dataList.get(0);
        final int rowCount = firstLayerData.getRegionalRowCount();
        final int columnCount = firstLayerData.getRegionalColumnCount();
        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();

        if ((rowCount > 0) && (columnCount > 0)) {

            final int layersPerBlock = Math.max(columnCount, rowCount);
            final int[] blockSize = { columnCount, rowCount, layersPerBlock };
            final long[] blockDimensions = { blockSize[0], blockSize[1], blockSize[2] };
            final long[] datasetDimensions = { blockSize[0], blockSize[1], (long) stackBounds.getDeltaZ() };

            try (final N5Writer n5Writer = new N5FSWriter(basePath)) {

                createDataSet(dataList,
                              basePath,
                              datasetName,
                              stackMetaData,
                              stackResolutionUnit,
                              n5Writer,
                              datasetDimensions,
                              blockSize,
                              rowCount,
                              columnCount);

                saveBlocks(dataList,
                           datasetName,
                           numberOfLayers,
                           layersPerBlock,
                           blockDimensions,
                           rowCount,
                           columnCount,
                           n5Writer);

            } catch (final IOException e) {
                throw new RuntimeException(e);
            }

        }

        LOG.info("writeN5: exit, finished writing {}{}", basePath, datasetName);
    }

    private static void createDataSet(final List<CrossCorrelationWithNextRegionalData> dataList,
                                      final String basePath,
                                      final String datasetName,
                                      final StackMetaData stackMetaData,
                                      final String stackResolutionUnit,
                                      final N5Writer n5Writer,
                                      final long[] datasetDimensions,
                                      final int[] blockSize,
                                      final int rowCount,
                                      final int columnCount)
            throws IOException {

        LOG.info("createDataSet: entry");

        n5Writer.createDataset(datasetName,
                               datasetDimensions,
                               blockSize,
                               DataType.FLOAT32,
                               new GzipCompression());

        final Map<String, Object> exportAttributes = new HashMap<>();
        exportAttributes.put("runTimestamp", new Date());
        exportAttributes.put("stackMetadata", stackMetaData);
        exportAttributes.put("rowCount", rowCount);
        exportAttributes.put("columnCount", columnCount);

        final Map<String, Object> attributes = new HashMap<>();
        attributes.put("crossCorrelationExport", exportAttributes);
        n5Writer.setAttributes(datasetName, attributes);

        final Path attributesPath = Paths.get(basePath, datasetName, "attributes.json").toAbsolutePath();
        LOG.info("createDataSet: saved {}", attributesPath);

        final NeuroglancerAttributes ngAttributes = buildNgAttributes(stackMetaData,
                                                                      stackResolutionUnit,
                                                                      rowCount,
                                                                      columnCount);
        ngAttributes.write(Paths.get(basePath), Paths.get(datasetName));

        final String dataSetPath = attributesPath.getParent().toString();
        final Path ccDataPath = Paths.get(dataSetPath, "cc_regional_data.json.gz");
        FileUtil.saveJsonFile(ccDataPath.toString(), dataList);

        final DoubleSummaryStatistics nonZeroCorrelationStats =
                dataList.stream()
                        .flatMapToDouble(CrossCorrelationWithNextRegionalData::flatMapRegionalCorrelation)
                        .filter(c -> c > 0.0)
                        .summaryStatistics();

        LOG.info("createDataSet: nonZeroCorrelationStats are {}", nonZeroCorrelationStats);

        if (basePath.startsWith("/nrs/")) {
            final Double exportMinZ = dataList.stream()
                    .map(CrossCorrelationWithNextRegionalData::getpZ)
                    .min(Double::compareTo)
                    .orElse(1.0);
            final String ngUrlString = buildNgUrlString(stackMetaData,
                                                        basePath,
                                                        datasetName,
                                                        exportMinZ,
                                                        nonZeroCorrelationStats) + "\n";
            final Path ngUrlPath = Paths.get(dataSetPath, "ng-url.txt");
            try {
                Files.write(ngUrlPath, ngUrlString.getBytes());
                LOG.info("createDataSet: neuroglancer URL written to {}", ngUrlPath);
            } catch (final IOException e) {
                LOG.warn("ignoring failure to write " + ngUrlPath, e);
            }
        }

        LOG.info("createDataSet: exit");
    }

    private static NeuroglancerAttributes buildNgAttributes(final StackMetaData stackMetaData,
                                                            final String stackResolutionUnit,
                                                            final int rowCount,
                                                            final int columnCount) {

        final List<Double> stackResolutionValues = stackMetaData.getCurrentResolutionValues();
        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();
        final double pixelsPerColumn = stackBounds.getWidth() / (double) columnCount;
        final double columnResolution = pixelsPerColumn * stackResolutionValues.get(0);
        final double pixelsPerRow = stackBounds.getHeight() / (double) rowCount;
        final double rowResolution = pixelsPerRow * stackResolutionValues.get(1);
        final List<Double> ccVolumeResolutionValues =
                Arrays.asList(columnResolution, rowResolution, stackResolutionValues.get(2));

        final List<Long> translationList = Arrays.asList(stackBounds.getMinX().longValue(),
                                                         stackBounds.getMinY().longValue(),
                                                         stackBounds.getMinZ().longValue());

        // save additional parameters so that n5 can be viewed in neuroglancer
        return new NeuroglancerAttributes(ccVolumeResolutionValues,
                                          stackResolutionUnit,
                                          0,
                                          new int[0],
                                          translationList,
                                          NeuroglancerAttributes.NumpyContiguousOrdering.FORTRAN);
    }

    private static void saveBlocks(final List<CrossCorrelationWithNextRegionalData> dataList,
                                   final String datasetName,
                                   final int numberOfLayers,
                                   final int layersPerBlock,
                                   final long[] blockDimensions,
                                   final int rowCount,
                                   final int columnCount,
                                   final N5Writer n5Writer)
            throws IOException {

        LOG.info("saveBlocks: entry");

        int savedBlockCount = 0;

        for (int layerIndex = 0; layerIndex < numberOfLayers; layerIndex += layersPerBlock) {
            
            final long[] gridOffset = { 0, 0, savedBlockCount };
            final ArrayImg<FloatType, FloatArray> block = ArrayImgs.floats(blockDimensions);
            final Cursor<FloatType> out = block.cursor();

            for (int layerOffset = 0; layerOffset < layersPerBlock; layerOffset++) {

                final int i = layerIndex + layerOffset;
                final CrossCorrelationWithNextRegionalData ccWithNext = dataList.get(i);
                final double[][] regionalCorrelation = ccWithNext.getRegionalCorrelation();

                for (int row = 0; row < rowCount; row++) {
                    for (int column = 0; column < columnCount; column++) {
                        final FloatType outValue = out.next();
                        final double correlation = regionalCorrelation[row][column];
                        if (correlation > 0.0) {
                            outValue.set((float) correlation);
                        }
                    }
                }

                if (i == (numberOfLayers - 1)) {
                    break;
                }
            }

            N5Utils.saveNonEmptyBlock(block, n5Writer, datasetName, gridOffset, new FloatType());
            savedBlockCount++;

            if (savedBlockCount % 20 == 0) {
                final int savedLayerCount = Math.min(numberOfLayers, (layerIndex + layersPerBlock));
                LOG.info("saveBlocks: saved {} blocks for {} out of {} z layers",
                         savedBlockCount, savedLayerCount, numberOfLayers);
            }
        }
        LOG.info("saveBlocks: exit");
    }

    private static String buildNgUrlString(final StackMetaData stackMetaData,
                                           final String basePath,
                                           final String datasetName,
                                           final Double exportMinZ,
                                           final DoubleSummaryStatistics nonZeroCorrelationStats) {

        if (! basePath.startsWith("/nrs/")) {
            throw new IllegalArgumentException("current implementation assumes Janelia deployment on /nrs, " +
                                               "so it won't work with a base path of " + basePath);
        }

        // basePath = /nrs/cellmap/data/jrc_ut23-0590-001/jrc_ut23-0590-001.n5 -> /n5_sources/cellmap/data/...
        final String n5Sources = "/n5_sources" + basePath.substring(4);

        // .../v1_acquire_align___20230908_083213_cc/s0 -> v1_acquire_align___20230908_083213_cc
        final String ngLayerName = "cc___" + stackMetaData.getStackId().getStack();

        final List<Double> res = stackMetaData.getCurrentResolutionValues();
        final String stackDimensions = "\"x\":[" + res.get(0).intValue() + "e-9,\"m\"]," +
                                       "\"y\":[" + res.get(1).intValue() + "e-9,\"m\"]," +
                                       "\"z\":[" + res.get(2).intValue() + "e-9,\"m\"]";

        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();
        final Bounds exportBounds = stackBounds.withMinZ(exportMinZ + 0.5); // TODO: figure out why need to add 0.5
        final String positionAndScales = buildPositionAndScales(exportBounds,
                                                                32,
                                                                32768);
        final String matrix = "[1,0,0," + stackBounds.getX() + "],[0,1,0," + stackBounds.getY() +
                              "],[0,0,1," + exportMinZ.intValue() + "]";
        final String rendererUrl = "http://renderer.int.janelia.org:8080";
        final String renderExportLayer = buildNgLayerStringForLatestRenderExport(stackMetaData.getStackId(),
                                                                                 stackBounds,
                                                                                 basePath,
                                                                                 rendererUrl,
                                                                                 stackDimensions);
        @SuppressWarnings("SpellCheckingInspection")
        final String encodedViridisShader = "%22shader%22:%22#uicontrol%20invlerp%20normalized%5Cn%5Cnvec3%20viridis_quintic%28%20float%20x%20%29%5Cn%7B%5Cn%5Ctvec4%20x1%20=%20vec4%28%201.0%2C%20x%2C%20x%20%2A%20x%2C%20x%20%2A%20x%20%2A%20x%20%29%3B%20//%201%20x%20x2%20x3%5Cn%5Ctvec4%20x2%20=%20x1%20%2A%20x1.w%20%2A%20x%3B%20//%20x4%20x5%20x6%20x7%5Cn%5Ctreturn%20vec3%28%5Cn%5Ct%5Ctdot%28%20x1.xyzw%2C%20vec4%28%20+0.280268003%2C%20-0.143510503%2C%20+2.225793877%2C%20-14.815088879%20%29%20%29%20+%20dot%28%20x2.xy%2C%20vec2%28%20+25.212752309%2C%20-11.772589584%20%29%20%29%2C%5Cn%5Ct%5Ctdot%28%20x1.xyzw%2C%20vec4%28%20-0.002117546%2C%20+1.617109353%2C%20-1.909305070%2C%20+2.701152864%20%29%20%29%20+%20dot%28%20x2.xy%2C%20vec2%28%20-1.685288385%2C%20+0.178738871%20%29%20%29%2C%5Cn%5Ct%5Ctdot%28%20x1.xyzw%2C%20vec4%28%20+0.300805501%2C%20+2.614650302%2C%20-12.019139090%2C%20+28.933559110%20%29%20%29%20+%20dot%28%20x2.xy%2C%20vec2%28%20-33.491294770%2C%20+13.762053843%20%29%20%29%20%29%3B%5Cn%7D%5Cn%20%20%5Cnvoid%20main%28%29%20%7B%5Cn%20%20vec3%20color%20=%20viridis_quintic%28normalized%28%29%29%3B%5Cn%20%20float%20alpha%20=%20normalized%28%29%3B%5Cn%20%20emitRGBA%28vec4%28color%2C%20alpha%29%29%3B%5Cn%7D%5Cn%22%2C";

        final String shaderControls = // note: ng range precision needs to be <= 6 to avoid json parse error
                String.format("\"shaderControls\":{\"normalized\":{\"range\":[%8.6f,%8.6f]}}",
                              nonZeroCorrelationStats.getMin(), nonZeroCorrelationStats.getMax());

        final String ngJsonPreShader =
                "{\"dimensions\":{" + stackDimensions + "}," + positionAndScales +
                ",\"layers\":[" + renderExportLayer + "{\"type\":\"image\",\"source\":{\"url\":\"n5://" +
                rendererUrl + n5Sources + datasetName + "\"," +
                "\"transform\":{\"matrix\":[" + matrix + "],\"outputDimensions\":{" + stackDimensions + "}}}," +
                "\"tab\":\"rendering\",";
        final String ngJsonPostShader =
                shaderControls + ",\"name\":\"" + ngLayerName + "\"}]," +
                "\"selectedLayer\":{\"size\":460,\"visible\":true,\"layer\":\"" + ngLayerName + "\"},\"layout\":\"4panel\"}";

        return rendererUrl + "/ng/#!" + URLEncoder.encode(ngJsonPreShader, StandardCharsets.UTF_8) +
               encodedViridisShader + URLEncoder.encode(ngJsonPostShader, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("SameParameterValue")
    private static String buildNgLayerStringForLatestRenderExport(final StackId stackId,
                                                                  final Bounds stackBounds,
                                                                  final String basePath,
                                                                  final String rendererUrl,
                                                                  final String stackDimensions) {
        final String exportPrefix = stackId.getStack() + "___";

        // /render/jrc_ut23_0590_001/v1_acquire_align___20230814_111246
        final File renderExportProjectDir =
                Paths.get(basePath, "render", stackId.getProject()).toAbsolutePath().toFile();

        Path latestExport = null;
        if (renderExportProjectDir.isDirectory()) {
            try (final Stream<Path> pathStream = Files.list(renderExportProjectDir.toPath())) {
                latestExport = pathStream.filter(p -> p.getFileName().toString().startsWith(exportPrefix))
                        .max(Comparator.naturalOrder())
                        .orElse(null);
                if (latestExport == null) {
                    LOG.warn("buildNgLayerStringForLatestRenderExport: failed to find directory starting with {} in {}",
                             exportPrefix, renderExportProjectDir);
                }
            } catch (final IOException e) {
                LOG.warn("ignoring failure to list files in " + renderExportProjectDir, e);
            }
        } else {
            LOG.warn("buildNgLayerStringForLatestRenderExport: failed to find render export directory {}",
                     renderExportProjectDir);
        }

        String layerString = "";
        if (latestExport != null) {
            final String matrix = "[1,0,0," + stackBounds.getX() + "],[0,1,0," + stackBounds.getY() +
                                  "],[0,0,1," + stackBounds.getMinZ().intValue() + "]";
            // /nrs/cellmap/data/jrc_ut23-0590-001... -> /n5_sources/cellmap/data/jrc_ut23-0590-001...
            final String n5Sources = "/n5_sources" + latestExport.toString().substring(4);

            layerString = "{\"type\":\"image\",\"source\":{\"url\":\"n5://" +
                          rendererUrl + n5Sources + "\"," + "\"transform\":{\"matrix\":[" +
                          matrix + "],\"outputDimensions\":{" + stackDimensions +
                          "}}},\"tab\":\"source\",\"name\":\"" + latestExport.getFileName() + "\"},";
        }

        return layerString;
    }
    @SuppressWarnings("CommentedOutCode")
    public static void main(final String[] args) {
//        try {
//            final String baseOutputPath = "/Users/trautmane/Desktop/test-ic.n5";
//            final String zCorrDir = "/Users/trautmane/Desktop/zcorr";
//            final String owner = "hess_wafer_53";
//            final String project = "cut_000_to_009";
//            final String stack = "c000_s095_v01_align";
//            final String run = "run_20230726_161543";
//
//            final String baseOutputPath = "/nrs/cellmap/data/jrc_ut23-0590-001/jrc_ut23-0590-001.n5";
//            final String zCorrDir = "/nrs/cellmap/data/jrc_ut23-0590-001/z_corr";
//            final String owner = "cellmap";
//            final String project = "jrc_ut23_0590_001";
//            final String stack = "v1_acquire_align";
//            final String run = "run_20230803_092548_69_z_corr";
//
//            final Path runPath = Paths.get(zCorrDir, owner, project, stack, run);
//            final List<CrossCorrelationWithNextRegionalData> dataList =
//                    CrossCorrelationWithNextRegionalData.loadDataDirectory(runPath);
//
//            final RenderDataClient renderDataClient =
//                    new RenderDataClient("http://renderer-dev.int.janelia.org:8080/render-ws/v1",
//                                         owner,
//                                         project);
//
//            final StackMetaData stackMetaData = renderDataClient.getStackMetaData(stack);
//            final String datasetName = "/cc/regional/" + project + "/" + stack + "/" + run + "/s0";
//
//            writeN5(dataList,
//                    baseOutputPath,
//                    datasetName,
//                    stackMetaData,
//                    "nm");
//        } catch (final Throwable t) {
//            t.printStackTrace();
//        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CrossCorrelationWithNextRegionalDataN5Writer.class);

}
