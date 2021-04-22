package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.Grid;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.zspacing.ThicknessCorrectionData;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import static org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid.N5ScalePyramidSpark.downsampleScalePyramid;

/**
 * Export a render stack to N5.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class N5Client {

    @SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal"})
    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--n5Path",
                description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5",
                required = true)
        public String n5Path;

        @Parameter(
                names = "--n5Dataset",
                description = "N5 dataset, e.g. /Sec26",
                required = true)
        public String baseDatasetName;

        public String getDatasetName() {
            return tempTileSizeString == null ? baseDatasetName : baseDatasetName + "-slices";
        }

        @Parameter(
                names = "--tileWidth",
                description = "Width of input tiles, e.g. 8192",
                required = true)
        public Integer tileWidth;

        @Parameter(
                names = "--tileHeight",
                description = "Width of input tiles, e.g. 8192",
                required = true)
        public Integer tileHeight;

        @Parameter(
                names = "--tempTileSize",
                description = "Size of temporary output tiles, must be an integer multiple of blockSize, e.g. 4096,4096")
        public String tempTileSizeString;

        public int[] getTempTileSize() {
            return parseCSIntArray(tempTileSizeString);
        }

        @Parameter(
                names = "--blockSize",
                description = "Size of output blocks, e.g. 128,128,128",
                required = true)
        public String blockSizeString;

        public int[] getBlockSize() {
            final int[] blockSize;
            final int[] tempTileSize = getTempTileSize();
            if (tempTileSize == null) {
                blockSize = parseCSIntArray(blockSizeString);
            } else {
                blockSize = new int[]{tempTileSize[0], tempTileSize[1], 1};
            }
            return blockSize;
        }

        @Parameter(
                names = "--factors",
                description = "Specifies generates a scale pyramid with given factors with relative scaling between factors, e.g. 2,2,2",
                required = true)
        public String downSamplingFactorsString;

        public int[] getDownSamplingFactors() {
            return parseCSIntArray(downSamplingFactorsString);
        }

        @ParametersDelegate
        public LayerBoundsParameters layerBounds = new LayerBoundsParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        public Bounds getBoundsForRun(final StackMetaData stackMetaData) {
            final Bounds defaultBounds = stackMetaData.getStats().getStackBounds();
            final Bounds defaultedLayerBounds = layerBounds.overrideBounds(defaultBounds);
            final Bounds runBounds = layerRange.overrideBounds(defaultedLayerBounds);
            LOG.info("getBoundsForRun: returning {}", runBounds);
            return runBounds;
        }

        @Parameter(
                names = "--z_coords",
                description = "Path of Zcoords.txt file",
                required = true)
        private String zCoordsPath = null;

        private int[] parseCSIntArray(final String csvString) {
            int[] intValues = null;
            if (csvString != null) {
                final String[] stringValues = csvString.split(",");
                intValues = new int[stringValues.length];
                for (int i = 0; i < stringValues.length; i++) {
                    intValues[i] = Integer.parseInt(stringValues[i]);
                }
            }
            return intValues;
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(N5Client.class);

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final N5Client.Parameters parameters = new N5Client.Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final N5Client client = new N5Client(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;

    public N5Client(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    public void run()
            throws IOException {

        final SparkConf conf = new SparkConf().setAppName("N5Client");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        final String datasetName = parameters.getDatasetName();
        final int[] blockSize = parameters.getBlockSize();
        final int[] downSamplingFactors = parameters.getDownSamplingFactors();
        final boolean downSampleStack = downSamplingFactors != null;

        final String fullScaleName = downSampleStack ? Paths.get(datasetName, "s" + 0).toString() : datasetName;

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        final Bounds bounds = parameters.getBoundsForRun(stackMetaData);

        final long[] min = {
                bounds.getMinX().longValue(),
                bounds.getMinY().longValue(),
                bounds.getMinZ().longValue()
        };
        final long[] size = {
                new Double(bounds.getDeltaX()).longValue(),
                new Double(bounds.getDeltaY()).longValue(),
                new Double(bounds.getDeltaZ()).longValue()
        };

        final File datasetDir = new File(Paths.get(parameters.n5Path, fullScaleName).toString());
        if (! datasetDir.exists()) {

            LOG.info("run: view stack command is n5_view.sh -i {} -d {} -o {},{},{}",
                     parameters.n5Path, fullScaleName, min[0], min[1], min[2]);

            final BoxRenderer boxRenderer = new BoxRenderer(parameters.renderWeb.baseDataUrl,
                                                            parameters.renderWeb.owner,
                                                            parameters.renderWeb.project,
                                                            parameters.stack,
                                                            parameters.tileWidth,
                                                            parameters.tileHeight,
                                                            1.0);

            final ThicknessCorrectionData thicknessCorrectionData =
                    parameters.zCoordsPath == null ? null : new ThicknessCorrectionData(parameters.zCoordsPath);

            // save full scale first ...
            saveRenderStack(
                    sparkContext,
                    boxRenderer,
                    parameters.tileWidth,
                    parameters.tileHeight,
                    parameters.n5Path,
                    fullScaleName,
                    min,
                    size,
                    blockSize,
                    thicknessCorrectionData);

        } else {
            final File s1Dir = new File(datasetDir.getParent(), "s1");
            if ((! downSampleStack) || (! fullScaleName.endsWith("/s0")) || s1Dir.exists()) {
                throw new IllegalArgumentException("Dataset " + datasetDir.getAbsolutePath() + " already exists.  " +
                                                   "Please remove the existing dataset if you wish to regenerate it.");
            }
        }

        if (downSampleStack) {

            LOG.info("run: down-sampling stack with factors {}", Arrays.toString(downSamplingFactors));

            // Now that the full resolution image is saved into n5, generate the scale pyramid
            final N5WriterSupplier n5Supplier = () -> new N5FSWriter(parameters.n5Path);

            // NOTE: no need to write full scale down-sampling factors (default is 1,1,1)

            downsampleScalePyramid(sparkContext,
                                   n5Supplier,
                                   fullScaleName,
                                   datasetName,
                                   downSamplingFactors);
        }

        // TODO: find out whether this should behave differently when down-sampling is requested
        if (parameters.getTempTileSize() != null) {
            reSave(sparkContext,
                   parameters.n5Path,
                   datasetName,
                   parameters.getDatasetName(),
                   parameters.getBlockSize());
        }

        sparkContext.close();
    }

    public static class BoxRenderer
            implements Serializable {

        private final String stackUrl;
        private final String boxUrlSuffix;

        public BoxRenderer(final String baseUrl,
                           final String owner,
                           final String project,
                           final String stack,
                           final long width,
                           final long height,
                           final double scale) {
            this.stackUrl = String.format("%s/owner/%s/project/%s/stack/%s", baseUrl, owner, project, stack);
            this.boxUrlSuffix = String.format("%d,%d,%f/render-parameters", width, height, scale);
        }

        public ByteProcessor render(final long x,
                                    final long y,
                                    final long z,
                                    final ImageProcessorCache ipCache) {
            final String renderParametersUrlString = String.format("%s/z/%d/box/%d,%d,%s",
                                                                   stackUrl, z, x, y, boxUrlSuffix);
            final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrlString);
            final BufferedImage image = renderParameters.openTargetImage();
            ArgbRenderer.render(renderParameters, image, ipCache);
            return new ColorProcessor(image).convertToByteProcessor();
        }
    }

    public static void saveRenderStack(
            final JavaSparkContext sc,
            final BoxRenderer boxRenderer,
            final int tileWidth,
            final int tileHeight,
            final String n5Path,
            final String datasetName,
            final long[] min,
            final long[] size,
            final int[] blockSize,
            final ThicknessCorrectionData thicknessCorrectionData)
            throws IOException {

        final N5Writer n5 = new N5FSWriter(n5Path);

        // TODO: setup neuroglancer friendly attributes for dataset

        n5.createDataset(
                datasetName,
                size,
                blockSize,
                DataType.UINT8,
                new GzipCompression());

        /*
         * grid block size for parallelization to minimize double loading of
         * blocks
         */
        final int[] gridBlockSize = new int[]{
                Math.max(blockSize[0], tileWidth),
                Math.max(blockSize[1], tileHeight),
                blockSize[2]
        };

        final JavaRDD<long[][]> rdd = sc.parallelize(
                Grid.create(
                        new long[]{
                                size[0],
                                size[1],
                                size[2]
                        },
                        gridBlockSize,
                        blockSize));

        rdd.foreach(gridBlock -> {

            // final ImageProcessorCache ipCache = new ImageProcessorCache();
            final ImageProcessorCache ipCache = ImageProcessorCache.DISABLED_CACHE;

            /* assume we can fit it in an array */
            final ArrayImg<UnsignedByteType, ByteArray> block = ArrayImgs.unsignedBytes(gridBlock[1]);

            final long x = gridBlock[0][0] + min[0];
            final long y = gridBlock[0][1] + min[1];

            ThicknessCorrectionData.LayerInterpolator priorInterpolator = null;
            ByteProcessor currentProcessor;
            ByteProcessor priorProcessor = null;
            ByteProcessor nextProcessor = null;
            for (int zIndex = 0; zIndex < block.dimension(2); zIndex++) {

                final long z = gridBlock[0][2] + min[2] + zIndex;

                if (thicknessCorrectionData == null) {
                    currentProcessor = boxRenderer.render(x, y, z, ipCache);
                } else {

                    final ThicknessCorrectionData.LayerInterpolator interpolator =
                            thicknessCorrectionData.getInterpolator(z);

                    if (priorInterpolator != null) {
                        if (interpolator.getPriorStackZ() == priorInterpolator.getNextStackZ()) {
                            priorProcessor = nextProcessor;
                            nextProcessor = null;
                        } else if (interpolator.getPriorStackZ() != priorInterpolator.getPriorStackZ()) {
                            priorProcessor = null;
                            nextProcessor = null;
                        } // else priorStackZ and nextStackZ have not changed, so reuse processors
                    }
                    priorInterpolator = interpolator;

                    if (priorProcessor == null) {
                        priorProcessor = boxRenderer.render(x, y, interpolator.getPriorStackZ(), ipCache);
//                    } else {
//                        LOG.info("priorProcessor already exists for z " + z + " (" + x + "," + y + ")");
                    }

                    if (interpolator.needsInterpolation()) {

                        currentProcessor = new ByteProcessor(priorProcessor.getWidth(), priorProcessor.getHeight());

                        if (nextProcessor == null) {
                            nextProcessor = boxRenderer.render(x, y, interpolator.getNextStackZ(), ipCache);
//                        } else {
//                            LOG.info("nextProcessor already exists for z " + z + " (" + x + "," + y + ")");
                        }

                        final int totalPixels = currentProcessor.getWidth() * currentProcessor.getHeight();
                        for (int pixelIndex = 0; pixelIndex < totalPixels; pixelIndex++) {
                            final double intensity = interpolator.deriveIntensity(priorProcessor.get(pixelIndex),
                                                                                  nextProcessor.get(pixelIndex));
                            currentProcessor.set(pixelIndex, (int) intensity);
                        }

                    } else {
                        currentProcessor = priorProcessor;
                    }

                }

                final IntervalView<UnsignedByteType> outSlice = Views.hyperSlice(block, 2, zIndex);
                final IterableInterval<UnsignedByteType> inSlice = Views
                        .flatIterable(
                                Views.interval(
                                        ArrayImgs.unsignedBytes(
                                                (byte[]) currentProcessor.getPixels(),
                                                currentProcessor.getWidth(),
                                                currentProcessor.getHeight()),
                                        outSlice));

                final Cursor<UnsignedByteType> in = inSlice.cursor();
                final Cursor<UnsignedByteType> out = outSlice.cursor();
                while (out.hasNext()) {
                    out.next().set(in.next());
                }
            }

            final N5FSWriter n5Writer = new N5FSWriter(n5Path);
            N5Utils.saveNonEmptyBlock(block, n5Writer, datasetName, gridBlock[2], new UnsignedByteType(0));
        });
    }

    /**
     * Copy an existing N5 dataset into another with a different blockSize.
     * <p>
     * Parallelizes over blocks of [max(input, output)] to reduce redundant
     * loading.  If blockSizes are integer multiples of each other, no
     * redundant loading will happen.
     */
    @SuppressWarnings("unchecked")
    public static void reSave(
            final JavaSparkContext sc,
            final String n5Path,
            final String datasetName,
            final String outDatasetName,
            final int[] outBlockSize)
            throws IOException {

        final N5Writer n5 = new N5FSWriter(n5Path);

        final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
        final int[] blockSize = attributes.getBlockSize();

        n5.createDataset(
                outDatasetName,
                attributes.getDimensions(),
                outBlockSize,
                attributes.getDataType(),
                attributes.getCompression());

        /* grid block size for parallelization to minimize double loading of blocks */
        final int[] gridBlockSize = new int[outBlockSize.length];
        Arrays.setAll(gridBlockSize, i -> Math.max(blockSize[i], outBlockSize[i]));

        final JavaRDD<long[][]> rdd =
                sc.parallelize(
                        Grid.create(
                                attributes.getDimensions(),
                                gridBlockSize,
                                outBlockSize));

        rdd.foreach(
                gridBlock -> {
                    final N5Writer n5Writer = new N5FSWriter(n5Path);
                    final RandomAccessibleInterval<?> source = N5Utils.open(n5Writer, datasetName);
                    @SuppressWarnings("rawtypes") final RandomAccessibleInterval sourceGridBlock =
                            Views.offsetInterval(source, gridBlock[0], gridBlock[1]);
                    N5Utils.saveBlock(sourceGridBlock, n5Writer, outDatasetName, gridBlock[2]);
                });
    }

}
