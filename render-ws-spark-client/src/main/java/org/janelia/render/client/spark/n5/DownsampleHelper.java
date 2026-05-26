package org.janelia.render.client.spark.n5;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.util.NeuroglancerAttributes;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper for downsampling a dataset.
 */
public class DownsampleHelper
        implements Serializable {

    private final String basePathOrStorageUrl;
    private final String sZeroDatasetPath;
    private final int[] downsampleFactors;
    private final int requiredSLevel;
    private final List<Double> stackResolutionValues;
    private final String stackResolutionUnit;
    private final List<Long> translatePixels;
    private final N5RetryUtil.RetryParameters retryParameters;

    /**
     * @param  basePathOrStorageUrl  base path or storage URL
     *                                 e.g. gs://janelia-spark-test/hess_wafers_60_61_export or
     *                                      /nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5
     *
     * @param  sZeroDatasetPath      full-resolution dataset path
     *                                 e.g. /flat/w61_serial_070_to_079/w61_s076_r00/raw_clahe/s0
     *
     * @param  downsampleFactors     per-dimension factors applied at each downsampling step (e.g. 2,2,1).
     *
     * @param  requiredSLevel        the minimum s-level that must be produced (e.g. 9).
     *
     * @param  retryParameters       parameters controlling retry behavior on failure
     *                               (specify as null if you do not want retries performed).
     *
     * @throws IOException
     *   if the sZeroDatasetPath does not end with '/s0'.
     */
    public DownsampleHelper(final String basePathOrStorageUrl,
                            final String sZeroDatasetPath,
                            final int[] downsampleFactors,
                            final int requiredSLevel,
                            final List<Double> stackResolutionValues,
                            final String stackResolutionUnit,
                            final List<Long> translatePixels,
                            final N5RetryUtil.RetryParameters retryParameters)
            throws IOException {

        this.basePathOrStorageUrl = basePathOrStorageUrl;

        this.sZeroDatasetPath = sZeroDatasetPath;
        if (! sZeroDatasetPath.endsWith("/s0")) {
            throw new IOException("sZeroDatasetPath must end with '/s0'");
        }

        this.downsampleFactors = downsampleFactors;
        this.requiredSLevel = requiredSLevel;
        this.stackResolutionValues = stackResolutionValues;
        this.stackResolutionUnit = stackResolutionUnit;
        this.translatePixels = translatePixels;
        this.retryParameters = retryParameters;
    }

    /**
     * Downsamples the N5 dataset iteratively, creating s1, s2, ... and writing neuroglancer attributes.
     * Downsampling will stop when the sN result contains a single block is greater than or equal to the requiredSLevel.
     *
     * @param  sparkContext  the Spark context used for distributed processing.
     *
     * @throws IOException
     *   if an N5 read or write operation fails.
     */
    public void run(final JavaSparkContext sparkContext)
            throws IOException {

        LOG.info("run: entry, basePathOrStorageUrl={}, sZeroDatasetPath={}, downsampleFactors={}, requiredSLevel={}, retryParameters={}",
                 basePathOrStorageUrl, sZeroDatasetPath, Arrays.toString(downsampleFactors), requiredSLevel, retryParameters);

        final N5WriterSupplier n5Supplier = () ->
                new N5Factory().openWriter(N5Factory.StorageFormat.N5, basePathOrStorageUrl);

        final N5Writer n5 = n5Supplier.get();
        final DatasetAttributes fullScaleAttributes = n5.getDatasetAttributes(sZeroDatasetPath);
        final long[] dimensions = fullScaleAttributes.getDimensions();
        final int numberOfDimensions = dimensions.length;
        final int[] outputBlockSize = fullScaleAttributes.getBlockSize();
        final String outputGroupPath = sZeroDatasetPath.substring(0, sZeroDatasetPath.lastIndexOf('/'));

        int numberOfDownsampledDatasets = 0;
        long downsampledBlockCount = 2;
        for (int scale = 1; (downsampledBlockCount > 1) || scale <= requiredSLevel; scale++) {

            final String fromDataset = scale == 1 ? sZeroDatasetPath : outputGroupPath + "/s" + (scale - 1);
            final String toDataset = outputGroupPath + "/s" + scale;

            final int[] scaleFactors = new int[numberOfDimensions];
            for (int d = 0; d < numberOfDimensions; d++) {
                scaleFactors[d] = (int) Math.round(Math.pow(downsampleFactors[d], scale));
            }

            long blockCount = 1;
            final long[] downsampledDimensions = new long[numberOfDimensions];
            for (int d = 0; d < numberOfDimensions; d++) {
                downsampledDimensions[d] = dimensions[d] / scaleFactors[d];
                final long blocksInDim = (downsampledDimensions[d] + outputBlockSize[d] - 1) / outputBlockSize[d];
                blockCount *= blocksInDim;
            }
            downsampledBlockCount = blockCount;

            if (n5.datasetExists(toDataset)) {

                final DatasetAttributes toDatasetAttributes = n5.getDatasetAttributes(toDataset);
                final long[] toDatasetDimensions = toDatasetAttributes.getDimensions();
                for (int d = 0; d < numberOfDimensions; d++) {
                    if (toDatasetDimensions[d] != downsampledDimensions[d]) {
                        throw new IOException(
                                "existing dataset " + toDataset + " has " + toDatasetDimensions[d] +
                                " pixels in axis " + d + " instead of " + downsampledDimensions[d] +
                                " pixels (based on downsampleFactor " + downsampleFactors[d] + ")");
                    }
                }

                LOG.info("run: skipping s{} because {} already exists", scale, toDataset);
                numberOfDownsampledDatasets++;

                continue;
            }

            final String operationDescription = "downsample " + fromDataset + " to " + toDataset;
            LOG.info("run: {} with {} downsampled block(s)", operationDescription, downsampledBlockCount);

            if (retryParameters == null) {
                N5DownsamplerSpark.downsample(sparkContext,
                                              n5Supplier,
                                              fromDataset,
                                              toDataset,
                                              downsampleFactors,
                                              null);
            } else {

                try {
                    final RetryStats retryStats = N5RetryUtil.executeWithRetryVoid(
                            () -> N5DownsamplerSpark.downsample(sparkContext,
                                                                n5Supplier,
                                                                fromDataset,
                                                                toDataset,
                                                                downsampleFactors),
                            retryParameters,
                            operationDescription);

                    LOG.info("run: {}", retryStats);

                } catch (final Exception e) {
                    throw new IOException(e);
                }

            }

            numberOfDownsampledDatasets++;
        }

        // save additional parameters so that n5 can be viewed in neuroglancer
        final NeuroglancerAttributes ngAttributes =
                new NeuroglancerAttributes(stackResolutionValues,
                                           stackResolutionUnit,
                                           numberOfDownsampledDatasets,
                                           downsampleFactors,
                                           translatePixels,
                                           NeuroglancerAttributes.NumpyContiguousOrdering.FORTRAN);

        ngAttributes.write(n5Supplier.get(), Paths.get(sZeroDatasetPath));

        LOG.info("run: exit, generated {} downsampled datasets for {}", numberOfDownsampledDatasets, outputGroupPath);
    }

    public static class Parameters
            extends CommandLineParameters {

        @Parameter(
                names = "--basePathOrStorageUrl",
                description = "Base path or storage URL, e.g. gs://janelia-spark-test/hess_wafers_60_61_export or " +
                              "/nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5",
                required = true)
        public String basePathOrStorageUrl;

        @Parameter(
                names = "--fullResolutionDataset",
                description = "Full-resolution dataset path, e.g. /flat/w61_serial_070_to_079/w61_s076_r00/raw_clahe/s0",
                required = true)
        public String fullResolutionDataset;

        @Parameter(
                names = "--factors",
                description = "Scale pyramid with given factors, e.g. 2,2,1",
                required = true)
        public String factors;

        @Parameter(
                names = "--requiredSLevel",
                description = "The minimum s-level that must be produced, e.g. 9")
        public int requiredSLevel = 0;

        @Parameter(
                names = "--stackResolution",
                description = "Resolution of the full scale x, y, and z axis pixels, e.g. 8,8,8",
                required = true)
        public String stackResolution;

        @Parameter(
                names = "--stackResolutionUnit",
                description = "Unit description for stack resolution values, e.g. nm, um, ...")
        public String stackResolutionUnit = "nm";

        @Parameter(
                names = "--translate",
                description = "Translation pixels for the full scale x, y, and z axis, e.g. 100,-77,1.  " +
                              "Omit if translation is not needed.")
        public String translate;

        public int[] getDownsampleFactors() {
            return Util.parseCSIntArray(factors);
        }

        public List<Double> getStackResolutionValues() {
            return Arrays.stream(Util.parseCSIntArray(stackResolution)).asDoubleStream()
                    .boxed().collect(Collectors.toList());

        }

        public List<Long> getTranslatePixels() {
            return Arrays.stream(Util.parseCSIntArray(translate)).asLongStream()
                    .boxed().collect(Collectors.toList());
        }
    }

    public static void main(final String[] args) throws Exception {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final DownsampleHelper helper = new DownsampleHelper(parameters.basePathOrStorageUrl,
                                                                     parameters.fullResolutionDataset,
                                                                     parameters.getDownsampleFactors(),
                                                                     parameters.requiredSLevel,
                                                                     parameters.getStackResolutionValues(),
                                                                     parameters.stackResolutionUnit,
                                                                     parameters.getTranslatePixels(),
                                                                     new N5RetryUtil.RetryParameters());

                final SparkConf conf = new SparkConf().setAppName("DownsampleHelper");
                final JavaSparkContext sparkContext = new JavaSparkContext(conf);
                helper.run(sparkContext);
                sparkContext.close();
            }
        };
        clientRunner.run();
    }

    private static final Logger LOG = LoggerFactory.getLogger(DownsampleHelper.class);

}
