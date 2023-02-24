package org.janelia.render.client.spark.n5;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.process.ByteProcessor;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.Grid;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.ImageProcessorCacheSpec;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.zspacing.ThicknessCorrectionData;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
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
        public String n5Dataset;

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
                names = "--blockSize",
                description = "Size of output blocks, e.g. 128,128,128",
                required = true)
        public String blockSizeString;

        public int[] getBlockSize() {
            return parseCSIntArray(blockSizeString);
        }

        @Parameter(
                names = "--isoFactors",
                description = "If specified, down samples the full resolution data with these factors " +
                              "before (optionally) generating a scale pyramid, e.g. 1,1,2")
        public String isoFactorsString;

        public int[] getIsoFactors() {
            return parseCSIntArray(isoFactorsString);
        }

        @Parameter(
                names = "--factors",
                description = "If specified, generates a scale pyramid with given factors, e.g. 2,2,2")
        public String downsampleFactorsString;

        public int[] getDownsampleFactors() {
            return parseCSIntArray(downsampleFactorsString);
        }

        @ParametersDelegate
        public LayerBoundsParameters layerBounds = new LayerBoundsParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--stackResolutionUnit",
                description = "Unit description for stack resolution values (e.g. nm, um, ...)")
        public String stackResolutionUnit = "nm";

        @Parameter(
                names = "--exportMask",
                description = "Export mask volume instead of pixel volume")
        public boolean exportMask = false;

        public Bounds getBoundsForRun(final Bounds defaultBounds,
                                      final ThicknessCorrectionData thicknessCorrectionData) {
            final Bounds defaultedLayerBounds = layerBounds.overrideBounds(defaultBounds);
            Bounds runBounds = layerRange.overrideBounds(defaultedLayerBounds);

            if (thicknessCorrectionData != null) {
                final double minZ = Math.ceil(Math.max(runBounds.getMinZ(),
                                                       Math.floor(thicknessCorrectionData.getFirstCorrectedZ())));
                final double maxZ = Math.floor(Math.min(runBounds.getMaxZ(),
                                                        Math.ceil(thicknessCorrectionData.getLastCorrectedZ())));
                runBounds = new Bounds(runBounds.getMinX(), runBounds.getMinY(), minZ,
                                       runBounds.getMaxX(), runBounds.getMaxY(), maxZ);
            }

            LOG.info("getBoundsForRun: returning {}", runBounds);
            return runBounds;
        }

        @Parameter(
                names = "--z_coords",
                description = "Path of Zcoords.txt file")
        public String zCoordsPath;

        @Parameter(
                names = "--minIntensity",
                description = "Min intensity for all render source tiles (omit to use default)"
        )
        public Double minIntensity;

        @Parameter(
                names = "--maxIntensity",
                description = "Max intensity for all render source tiles (omit to use default)"
        )
        public Double maxIntensity;

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

        public void validate() throws IllegalArgumentException {
            final int[] blockSize = getBlockSize();
            if (tileWidth % blockSize[0] != 0) {
                throw new IllegalArgumentException("tileWidth " + tileWidth +
                                                   " must be an integral multiple of the blockSize width " +
                                                   blockSize[0]);
            }
            if (tileHeight % blockSize[1] != 0) {
                throw new IllegalArgumentException("tileHeight " + tileHeight +
                                                   " must be an integral multiple of the blockSize height " +
                                                   blockSize[1]);
            }
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
                parameters.validate();

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

        String datasetName = parameters.n5Dataset;
        int[] blockSize = parameters.getBlockSize();
        final int[] downsampleFactors = parameters.getDownsampleFactors();
        final boolean downsampleStack = downsampleFactors != null;

        String fullScaleDatasetName = downsampleStack ?
                                      Paths.get(datasetName, "s" + 0).toString() : datasetName;

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        final List<Double> resolutionValues = stackMetaData.getCurrentResolutionValues();

        final ThicknessCorrectionData thicknessCorrectionData =
                parameters.zCoordsPath == null ? null : new ThicknessCorrectionData(parameters.zCoordsPath);

        final Bounds defaultBounds = stackMetaData.getStats().getStackBounds();
        final Bounds boundsForRun = parameters.getBoundsForRun(defaultBounds,
                                                               thicknessCorrectionData);

        long[] min = {
                boundsForRun.getMinX().longValue(),
                boundsForRun.getMinY().longValue(),
                boundsForRun.getMinZ().longValue()
        };
        long[] dimensions = {
                new Double(boundsForRun.getDeltaX()).longValue(),
                new Double(boundsForRun.getDeltaY()).longValue(),
                new Double(boundsForRun.getDeltaZ()).longValue()
        };

        String viewStackCommandOffsets = min[0] + "," + min[1] + "," + min[2];

        final boolean is2DVolume = (boundsForRun.getDeltaZ() < 1);

        if (is2DVolume) {
            resolutionValues.remove(2);
            min = new long[] { min[0], min[1] };
            dimensions = new long[] { dimensions[0], dimensions[1] };
            blockSize = new int[] { blockSize[0], blockSize[1] };
            viewStackCommandOffsets = min[0] + "," + min[1];
        }
        
        final File datasetDir = new File(Paths.get(parameters.n5Path, fullScaleDatasetName).toString());
        if (! datasetDir.exists()) {

            LOG.info("run: view stack command is n5_view.sh -i {} -d {} -o {}",
                     parameters.n5Path, datasetName, viewStackCommandOffsets);

            // save full scale first ...
            setupFullScaleExportN5(parameters, fullScaleDatasetName, stackMetaData, dimensions, blockSize);

            final BoxRenderer boxRenderer = new BoxRenderer(parameters.renderWeb.baseDataUrl,
                                                            parameters.renderWeb.owner,
                                                            parameters.renderWeb.project,
                                                            parameters.stack,
                                                            parameters.tileWidth,
                                                            parameters.tileHeight,
                                                            1.0,
                                                            parameters.minIntensity,
                                                            parameters.maxIntensity,
                                                            parameters.exportMask);

            // This "spec" is broadcast to Spark executors allowing each one to construct and share
            // an ImageProcessor cache across each task assigned to the executor.
            // This should be most useful for caching common masks but could also help
            // when the same executor gets adjacent blocks.
            // Cache parameters are hard-coded for now but could be exposed to command-line later if necessary.
            final ImageProcessorCacheSpec cacheSpec =
                    new ImageProcessorCacheSpec(ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS,
                                                true,
                                                false);

            if (is2DVolume) {
                save2DRenderStack(
                        sparkContext,
                        boxRenderer,
                        parameters.tileWidth,
                        parameters.tileHeight,
                        parameters.n5Path,
                        fullScaleDatasetName,
                        min,
                        dimensions,
                        blockSize,
                        boundsForRun.getMinZ().longValue(),
                        cacheSpec);
            } else {
                saveRenderStack(
                        sparkContext,
                        boxRenderer,
                        parameters.tileWidth,
                        parameters.tileHeight,
                        parameters.n5Path,
                        fullScaleDatasetName,
                        min,
                        dimensions,
                        blockSize,
                        thicknessCorrectionData,
                        cacheSpec);
            }

        } else {
            final File s1Dir = new File(datasetDir.getParent(), "s1");
            if ((! downsampleStack) || s1Dir.exists()) {
                throw new IllegalArgumentException("Dataset " + datasetDir.getAbsolutePath() + " already exists.  " +
                                                   "Please remove the existing dataset if you wish to regenerate it.");
            }
        }

        if (parameters.isoFactorsString != null) {
            final int[] isoFactors = parameters.getIsoFactors();

            for (int i = 0; i < isoFactors.length; i++) {
                resolutionValues.set(i, resolutionValues.get(i) * isoFactors[i]);
            }

            final N5WriterSupplier n5Supplier = new N5PathSupplier(parameters.n5Path);

            final String isoDatasetName = datasetName + "___iso";
            final String fullScaleIsoDatasetName = downsampleStack ?
                                                   Paths.get(isoDatasetName, "s" + 0).toString() :
                                                   isoDatasetName;

            N5DownsamplerSpark.downsample(
                    sparkContext,
                    n5Supplier,
                    fullScaleDatasetName,
                    fullScaleIsoDatasetName,
                    isoFactors
            );

            datasetName = isoDatasetName;
            fullScaleDatasetName = fullScaleIsoDatasetName;
        }

        int numberOfDownSampledDatasets = 0;
        if (downsampleStack) {

            LOG.info("run: downsample stack with factors {}", Arrays.toString(downsampleFactors));

            // Now that the full resolution image is saved into n5, generate the scale pyramid
            final N5WriterSupplier n5Supplier = new N5PathSupplier(parameters.n5Path);

            final List<String> downsampledDatasetPaths =
                    downsampleScalePyramid(sparkContext,
                                           n5Supplier,
                                           fullScaleDatasetName,
                                           datasetName,
                                           downsampleFactors);

            numberOfDownSampledDatasets = downsampledDatasetPaths.size();
        }

        // save additional parameters so that n5 can be viewed in neuroglancer
        final NeuroglancerAttributes ngAttributes =
                new NeuroglancerAttributes(resolutionValues,
                                           parameters.stackResolutionUnit,
                                           numberOfDownSampledDatasets,
                                           downsampleFactors,
                                           Arrays.asList(min[0], min[1], min[2]),
                                           NeuroglancerAttributes.NumpyContiguousOrdering.FORTRAN);

        ngAttributes.write(Paths.get(parameters.n5Path),
                           Paths.get(fullScaleDatasetName));

        sparkContext.close();
    }

    public static void setupFullScaleExportN5(final Parameters parameters,
                                              final String fullScaleDatasetName,
                                              final StackMetaData stackMetaData,
                                              final long[] dimensions,
                                              final int[] blockSize)
            throws IOException {

        String exportAttributesDatasetName = fullScaleDatasetName;

        try (final N5Writer n5 = new N5FSWriter(parameters.n5Path)) {
            n5.createDataset(fullScaleDatasetName,
                             dimensions,
                             blockSize,
                             DataType.UINT8,
                             new GzipCompression());

            final Map<String, Object> export_attributes = new HashMap<>();
            export_attributes.put("runTimestamp", new Date());
            export_attributes.put("runParameters", parameters);
            export_attributes.put("stackMetadata", stackMetaData);

            final Map<String, Object> attributes = new HashMap<>();
            attributes.put("renderExport", export_attributes);

            final Path fullScaleDatasetPath = Paths.get(fullScaleDatasetName);
            if ("s0".equals(fullScaleDatasetPath.getFileName().toString())) {
                exportAttributesDatasetName = fullScaleDatasetPath.getParent().toString();
            }
            n5.setAttributes(exportAttributesDatasetName, attributes);
        }

        LOG.info("setupFullScaleExportN5: saved {}",
                 Paths.get(parameters.n5Path, exportAttributesDatasetName, "attributes.json"));
    }

    public static class BoxRenderer
            implements Serializable {

        private final String stackUrl;
        private final String boxUrlSuffix;
        private final Double minIntensity;
        private final Double maxIntensity;
        private final boolean exportMaskOnly;

        public BoxRenderer(final String baseUrl,
                           final String owner,
                           final String project,
                           final String stack,
                           final long width,
                           final long height,
                           final double scale,
                           final Double minIntensity,
                           final Double maxIntensity,
                           final boolean exportMaskOnly) {
            this.stackUrl = String.format("%s/owner/%s/project/%s/stack/%s", baseUrl, owner, project, stack);
            this.boxUrlSuffix = String.format("%d,%d,%f/render-parameters", width, height, scale);
            this.minIntensity = minIntensity;
            this.maxIntensity = maxIntensity;
            this.exportMaskOnly = exportMaskOnly;
        }

        public ByteProcessor render(final long x,
                                    final long y,
                                    final long z,
                                    final ImageProcessorCache ipCache) {
            final String renderParametersUrlString = String.format("%s/z/%d/box/%d,%d,%s",
                                                                   stackUrl, z, x, y, boxUrlSuffix);
            final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrlString);
            if (minIntensity != null) {
                renderParameters.setMinIntensity(minIntensity);
            }
            if (maxIntensity != null) {
                renderParameters.setMaxIntensity(maxIntensity);
            }

            final ByteProcessor renderedProcessor;
            if (renderParameters.numberOfTileSpecs() > 0) {
                if (exportMaskOnly) {
                    for (final TileSpec tileSpec : renderParameters.getTileSpecs()) {
                        tileSpec.replaceFirstChannelImageWithItsMask();
                    }
                }
                final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                        Renderer.renderImageProcessorWithMasks(renderParameters, ipCache);
                renderedProcessor = ipwm.ip.convertToByteProcessor();
            } else {
                LOG.info("BoxRenderer.render: no tiles found in {}", renderParametersUrlString);
                final double derivedScale = renderParameters.getScale();
                final int targetWidth = (int) (derivedScale * renderParameters.getWidth());
                final int targetHeight = (int) (derivedScale * renderParameters.getHeight());
                renderedProcessor = new ByteProcessor(targetWidth, targetHeight);
            }

            return renderedProcessor;
        }
    }

    // serializable downsample supplier for spark
    public static class N5PathSupplier implements N5WriterSupplier {
        private final String path;
        public N5PathSupplier(final String path) {
            this.path = path;
        }
        @Override
        public N5Writer get()
                throws IOException {
            return new N5FSWriter(path);
        }
    }

    private static void saveRenderStack(final JavaSparkContext sc,
                                        final BoxRenderer boxRenderer,
                                        final int tileWidth,
                                        final int tileHeight,
                                        final String n5Path,
                                        final String datasetName,
                                        final long[] min,
                                        final long[] dimensions,
                                        final int[] blockSize,
                                        final ThicknessCorrectionData thicknessCorrectionData,
                                        final ImageProcessorCacheSpec cacheSpec) {

        // grid block size for parallelization to minimize double loading of tiles
        final int[] gridBlockSize = new int[]{
                Math.max(blockSize[0], tileWidth),
                Math.max(blockSize[1], tileHeight),
                blockSize[2]
        };

        final JavaRDD<long[][]> rdd = sc.parallelize(
                Grid.create(
                        new long[] {
                                dimensions[0],
                                dimensions[1],
                                dimensions[2]
                        },
                        gridBlockSize,
                        blockSize));

        final Broadcast<ImageProcessorCacheSpec> broadcastCacheSpec = sc.broadcast(cacheSpec);

        rdd.foreach(gridBlock -> {

            final ImageProcessorCache ipCache = broadcastCacheSpec.getValue().getSharableInstance();

            /* assume we can fit it in an array */
            final ArrayImg<UnsignedByteType, ByteArray> block = ArrayImgs.unsignedBytes(gridBlock[1]);

            final long x = gridBlock[0][0] + min[0];
            final long y = gridBlock[0][1] + min[1];
            final long startZ = gridBlock[0][2] + min[2];

            // enable logging on executors and add gridBlock context to log messages
            LogUtilities.setupExecutorLog4j(x + ":" + y + ":" + startZ);

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

            final N5Writer anotherN5Writer = new N5FSWriter(n5Path); // needed to prevent Spark serialization error
            N5Utils.saveNonEmptyBlock(block, anotherN5Writer, datasetName, gridBlock[2], new UnsignedByteType(0));
        });
    }

    private static void save2DRenderStack(final JavaSparkContext sc,
                                          final BoxRenderer boxRenderer,
                                          final int tileWidth,
                                          final int tileHeight,
                                          final String n5Path,
                                          final String datasetName,
                                          final long[] min,
                                          final long[] dimensions,
                                          final int[] blockSize,
                                          final long z,
                                          final ImageProcessorCacheSpec cacheSpec) {

        LOG.info("save2DRenderStack: entry, z={}", z);

        // grid block size for parallelization to minimize double loading of tiles
        final int[] gridBlockSize = new int[]{
                Math.max(blockSize[0], tileWidth),
                Math.max(blockSize[1], tileHeight),
        };

        final JavaRDD<long[][]> rdd = sc.parallelize(
                Grid.create(
                        new long[] {
                                dimensions[0],
                                dimensions[1],
                        },
                        gridBlockSize,
                        blockSize));

        final Broadcast<ImageProcessorCacheSpec> broadcastCacheSpec = sc.broadcast(cacheSpec);
        
        rdd.foreach(gridBlock -> {

            final ImageProcessorCache ipCache = broadcastCacheSpec.value().getSharableInstance();

            /* assume we can fit it in an array */
            final ArrayImg<UnsignedByteType, ByteArray> block = ArrayImgs.unsignedBytes(gridBlock[1]);

            final long x = gridBlock[0][0] + min[0];
            final long y = gridBlock[0][1] + min[1];

            // enable logging on executors and add gridBlock context to log messages
            LogUtilities.setupExecutorLog4j(x + ":" + y + ":" + z);

            final ByteProcessor currentProcessor = boxRenderer.render(x, y, z, ipCache);

            final IterableInterval<UnsignedByteType> inSlice = Views
                    .flatIterable(
                            Views.interval(
                                    ArrayImgs.unsignedBytes(
                                            (byte[]) currentProcessor.getPixels(),
                                            currentProcessor.getWidth(),
                                            currentProcessor.getHeight()),
                                    block));

            final Cursor<UnsignedByteType> in = inSlice.cursor();
            final Cursor<UnsignedByteType> out = block.cursor();
            while (out.hasNext()) {
                out.next().set(in.next());
            }

            final N5Writer anotherN5Writer = new N5FSWriter(n5Path); // needed to prevent Spark serialization error
            N5Utils.saveNonEmptyBlock(block, anotherN5Writer, datasetName, gridBlock[2], new UnsignedByteType(0));
        });

        LOG.info("save2DRenderStack: exit");
    }

}
