package org.janelia.render.client;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.spec.Bounds;
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
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.type.numeric.integer.UnsignedByteType;

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

            // TODO: this is no longer true, consider larger z block size
            // need to write z layers concurrently, so make z block size 1
            final int[] blockSize = { columnCount, rowCount, 1 };
            final long[] blockDimensions = { blockSize[0], blockSize[1], blockSize[2] };
            final long[] datasetDimensions = { blockSize[0], blockSize[1], (long) stackBounds.getDeltaZ() };

            try (final N5Writer n5Writer = new N5FSWriter(basePath)) {

                n5Writer.createDataset(datasetName,
                                       datasetDimensions,
                                       blockSize,
                                       DataType.UINT8,
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
                LOG.info("writeN5: saved {}", attributesPath);

                final Path ccDataPath = Paths.get(attributesPath.getParent().toString(), "cc_regional_data.json.gz");
                FileUtil.saveJsonFile(ccDataPath.toString(), dataList);

                final int margin = 55;
                final int minViz = 0;
                final int maxViz = 255 - margin;
                final int vizRange = maxViz - minViz;

                for (int i = 0; i < numberOfLayers; i++) {
                    final CrossCorrelationWithNextRegionalData ccWithNext = dataList.get(i);
                    final long[] gridOffset = { 0, 0, (long) (ccWithNext.getpZ() - stackBounds.getMinZ()) };
                    final ArrayImg<UnsignedByteType, ByteArray> block = ArrayImgs.unsignedBytes(blockDimensions);
                    final Cursor<UnsignedByteType> out = block.cursor();

                    final double[][] regionalCorrelation = ccWithNext.getRegionalCorrelation();

                    final DoubleSummaryStatistics nonZeroCorrelationStats =
                            Arrays.stream(regionalCorrelation)
                                    .flatMapToDouble(Arrays::stream)
                                    .filter(c -> c > 0.0)
                                    .summaryStatistics();

                    final double minCc = nonZeroCorrelationStats.getMin();
                    final double ccRange = nonZeroCorrelationStats.getMax() - minCc;

                    // System.out.println("stats=" + nonZeroCorrelationStats + ", ccRange=" + ccRange + ", vizRange=" + vizRange);

                    for (int row = 0; row < rowCount; row++) {
                        for (int column = 0; column < columnCount; column++) {
                            final UnsignedByteType outValue = out.next();
                            final double correlation = regionalCorrelation[row][column];
                            if (correlation > 0.0) {
                                final double relativeCc = correlation - minCc;
                                final double relativeViz = relativeCc * vizRange / ccRange;
                                //final double invertedRelativeViz = vizRange - relativeViz; // make worse correlation brighter
                                final int viz = (int) relativeViz + margin;
                                // System.out.println("[" + outValue.getIndex() + "] => " + correlation + ", " + viz);
                                outValue.set(viz);
                            }
                        }
                    }

                    N5Utils.saveNonEmptyBlock(block, n5Writer, datasetName, gridOffset, new UnsignedByteType());

                    if (i % 100 == 0) {
                        LOG.info("writeN5: saved blocks for {} out of {} z layers", (i+1), numberOfLayers);
                    }
                }

                final List<Double> stackResolutionValues = stackMetaData.getCurrentResolutionValues();
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
                final NeuroglancerAttributes ngAttributes =
                        new NeuroglancerAttributes(ccVolumeResolutionValues,
                                                   stackResolutionUnit,
                                                   0,
                                                   new int[0],
                                                   translationList,
                                                   NeuroglancerAttributes.NumpyContiguousOrdering.FORTRAN);

                ngAttributes.write(Paths.get(basePath),
                                   Paths.get(datasetName));

            } catch (final IOException e) {
                throw new RuntimeException(e);
            }

        }

        LOG.info("writeN5: exit, finished writing {}{}", basePath, datasetName);
    }

    @SuppressWarnings("CommentedOutCode")
    public static void main(final String[] args) {
/*
        try {
//            final String baseOutputPath = "/Users/trautmane/Desktop/test-ic.n5";
//            final String zCorrDir = "/Users/trautmane/Desktop/zcorr";
//            final String owner = "hess_wafer_53";
//            final String project = "cut_000_to_009";
//            final String stack = "c000_s095_v01_align";
//            final String run = "run_20230726_161543";


            final String baseOutputPath = "/nrs/cellmap/data/jrc_ut23-0590-001/jrc_ut23-0590-001.n5";
            final String zCorrDir = "/nrs/cellmap/data/jrc_ut23-0590-001/z_corr";
            final String owner = "cellmap";
            final String project = "jrc_ut23_0590_001";
            final String stack = "v1_acquire_align";
            final String run = "run_20230803_092548_69_z_corr";

            final Path runPath = Paths.get(zCorrDir, owner, project, stack, run);
            final List<CrossCorrelationWithNextRegionalData> dataList =
                    CrossCorrelationWithNextRegionalData.loadDataDirectory(runPath);

            final RenderDataClient renderDataClient =
                    new RenderDataClient("http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                                         owner,
                                         project);
            final StackMetaData stackMetaData = renderDataClient.getStackMetaData(stack);
            final String datasetName = "/cc/regional/" + project + "/" + stack + "/" + run + "/s0";

            writeN5(dataList,
                    baseOutputPath,
                    datasetName,
                    stackMetaData);
        } catch (final Throwable t) {
            t.printStackTrace();
        }

*/
    }

    private static final Logger LOG = LoggerFactory.getLogger(CrossCorrelationWithNextRegionalDataN5Writer.class);

}
