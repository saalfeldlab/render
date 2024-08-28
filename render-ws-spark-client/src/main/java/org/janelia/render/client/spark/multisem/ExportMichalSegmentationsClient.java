package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.NeuroglancerAttributes;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.multisem.ResaveSegmentations;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Export a render stack to N5.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ExportMichalSegmentationsClient implements Serializable {

    @SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal"})
    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stacks",
                description = "Stack names as list",
                required = true)
        public List<String> stacks;

        @Parameter(
                names = "--sourceN5Path",
                description = "N5 path of the segmentation data, e.g., /nrs/hess/data/hess_wafer_53/mapback_michal/base240715/test",
                required = true)
        public String sourceN5Path;

        @Parameter(
                names = "--sourceN5Dataset",
                description = "N5 dataset of the segmentation data, e.g., /n5",
                required = true)
        public String sourceN5Dataset;

        @Parameter(
                names = "--targetN5Path",
                description = "Path to the target N5 container, e.g., /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5",
                required = true)
        public String targetN5Path;

        @Parameter(
                names = "--targetN5Group",
                description = "N5 group in the target container where all stacks will be saved by stack name",
                required = true)
        public String targetN5Group;

        @Parameter(
                names = "--blockSize",
                description = "Size of output blocks, e.g. 128,128,128",
                required = true)
        public String blockSizeString;

        @Parameter(
                names = "--layerOriginCsv",
                description = "Path to the CSV file with layer origins create with LayerOrigin.main()",
                required = true)
        public String layerOriginCsv;

        @Parameter(
                names = "--nThreads",
                description = "Number of threads to use",
                required = true)
        public int nThreads;

        public int[] getBlockSize() {
            return parseCSIntArray(blockSizeString);
        }

        // Some parameters that likely won't change, so they are hard-coded here
        public String stackResolutionUnit = "nm";
        public String sourceStackSuffix = "_hayworth_alignment_replica";
        public String targetStackSuffix = "_align_no35";

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

    private static final Logger LOG = LoggerFactory.getLogger(ExportMichalSegmentationsClient.class);

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ExportMichalSegmentationsClient client = new ExportMichalSegmentationsClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public ExportMichalSegmentationsClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public Parameters getParameters() {
        return parameters;
    }

    public void run() throws IOException, IllegalArgumentException {

        final SparkConf conf = new SparkConf().setAppName("ExportMichalSegmentationsClient");
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {

            final String sparkAppId = sparkContext.getConf().getAppId();
            final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

            LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

            try (final N5Reader n5 = new N5FSReader(parameters.sourceN5Path)) {
                if (!n5.exists(parameters.sourceN5Dataset)) {
                    throw new IllegalArgumentException("run: source N5 dataset does not exist: " + parameters.sourceN5Dataset);
                }
            }

            if (Files.exists(Paths.get(parameters.targetN5Path))) {
                try (final N5Reader n5 = new N5FSReader(parameters.targetN5Path)) {
                    if (n5.exists(parameters.targetN5Group)) {
                        throw new IllegalArgumentException("run: target N5 group already exists: " + parameters.targetN5Group);
                    }
                }
            }

            sparkContext.parallelize(parameters.stacks)
                    .foreach(stack -> {
                        try {
                            runForStack(stack, parameters);
                        } catch (final Exception e) {
                            LOG.error("run: error processing stack {}", stack, e);
                        }
                    });
        }
    }

    private void runForStack(
			final String stack,
			final Parameters parameters
    ) throws IOException {

        final ResaveSegmentations resaveSegmentations = new ResaveSegmentations(
                parameters.renderWeb.baseDataUrl,
                parameters.renderWeb.owner,
                stack,
                parameters.sourceStackSuffix,
                parameters.targetStackSuffix,
                parameters.sourceN5Path,
                parameters.targetN5Path,
                parameters.sourceN5Dataset,
                parameters.targetN5Group,
                parameters.layerOriginCsv,
                parameters.getBlockSize(),
                parameters.nThreads);

        resaveSegmentations.run();

        final StackMetaData stackMetaData = resaveSegmentations.getTargetStackMetaData();
        final Bounds boundsForRun = stackMetaData.getStackBounds();
        final List<Double> resolutionValues = stackMetaData.getCurrentResolutionValues();

        final long[] min = {
                boundsForRun.getMinX().longValue(),
                boundsForRun.getMinY().longValue(),
                boundsForRun.getMinZ().longValue()
        };

        final String viewStackCommandOffsets = min[0] + "," + min[1] + "," + min[2];

        final String targetDataset = Paths.get(parameters.targetN5Group, stack).toString();
        LOG.info("run: view stack command is n5_view.sh -i {} -d {} -o {}",
                 parameters.targetN5Path, targetDataset, viewStackCommandOffsets);

		try (final N5Writer n5 = new N5FSWriter(parameters.targetN5Path)) {
            final Map<String, Object> exportAttributes = new HashMap<>();
            exportAttributes.put("runTimestamp", new Date());
            exportAttributes.put("runParameters", parameters);
            exportAttributes.put("stackMetadata", stackMetaData);

            final Map<String, Object> attributes = new HashMap<>();
            attributes.put("renderExport", exportAttributes);

            n5.setAttributes(targetDataset, attributes);
        }

        LOG.info("updateFullScaleExportAttributes: saved {}",
                 Paths.get(parameters.targetN5Path, targetDataset, "attributes.json"));

        final int numberOfDownSampledDatasets = 0;
        final int[] downSampleFactors = null;

        // save additional parameters so that n5 can be viewed in neuroglancer
        final NeuroglancerAttributes ngAttributes =
                new NeuroglancerAttributes(resolutionValues,
                                           parameters.stackResolutionUnit,
                                           numberOfDownSampledDatasets,
                                           downSampleFactors,
                                           Arrays.asList(min[0], min[1], min[2]),
                                           NeuroglancerAttributes.NumpyContiguousOrdering.FORTRAN);

        ngAttributes.write(Paths.get(parameters.targetN5Path), Paths.get(targetDataset, "s0"));
    }
}
