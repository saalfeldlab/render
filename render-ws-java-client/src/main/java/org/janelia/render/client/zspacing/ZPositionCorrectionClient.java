package org.janelia.render.client.zspacing;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.zspacing.loader.MaskedResinLayerLoader;
import org.janelia.render.client.zspacing.loader.RenderLayerLoader;
import org.janelia.thickness.inference.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.DoubleType;

/**
 * Java client for estimating z thickness of a range of layers in an aligned render stack.
 *
 * @author Eric Trautman
 */
public class ZPositionCorrectionClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--scale",
                description = "Scale to render each layer",
                required = true)
        public Double scale;

        @Parameter(
                names = "--rootDirectory",
                description = "Root directory for all output (e.g. /groups/flyem/data/alignment-ett/zcorr)",
                required = true)
        public String rootDirectory;

        @Parameter(
                names = "--runName",
                description = "Common run name to include in output path when running array jobs.  " +
                              "Typically includes the timestamp when the array job was created.  " +
                              "Omit if not running in an array job context.")
        public String runName;

        @Parameter(
                names = "--optionsJson",
                description = "JSON file containing thickness correction options (omit to use default values)")
        public String optionsJson;

        @Parameter(
                names = "--nLocalEstimates",
                description = "Number of local estimates")
        public Integer nLocalEstimates = 1;

        @Parameter(
                names = "--resinMaskingEnabled",
                description = "Specify as 'false' to skip masking of resin areas",
                arity = 1)
        public boolean resinMaskingEnabled = true;

        @Parameter(
                names = "--resinSigma",
                description = "Standard deviation for gaussian convolution")
        public Integer resinSigma = 100;

        @Parameter(
                names = "--resinContentThreshold",
                description = "Threshold intensity that identifies content")
        public Double resinContentThreshold = 3.0;

        @Parameter(
                names = "--resinMaskIntensity",
                description = "Intensity value to use when masking resin areas (typically max intensity for image)")
        public Float resinMaskIntensity = 255.0f;

        @Parameter(
                names = "--correlationBatch",
                description = "Specify to only save correlation data without solving.  " +
                              "Format is <batch number>:<total batch count> where first batch number is 1 " +
                              "(e.g. '1:20', '2:20', ..., '20:20').")
        public String correlationBatch;

        @Parameter(
                names = "--solveExisting",
                description = "Specify to load existing correlation data and solve.",
                arity = 0)
        public boolean solveExisting;

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @ParametersDelegate
        public LayerBoundsParameters bounds = new LayerBoundsParameters();

        @Parameter(
                names = "--debugFormat",
                description = "Indicates that rendered layer images should be saved to disk in this format " +
                              "(e.g. 'jpg', 'png', 'tif') for debugging (omit to avoid saving to disk)"
        )
        public String debugFormat;

        public Parameters() {
        }

        public Options getInferenceOptions()
                throws FileNotFoundException {
            return optionsJson == null ?
                   HeadlessZPositionCorrection.generateDefaultFIBSEMOptions() : Options.read(optionsJson);
        }

        private Integer currentBatchNumber = null;
        private Integer totalBatchCount = null;

        public void deriveBatchInfo()
                throws IllegalArgumentException {

            if (correlationBatch != null) {
                final String[] values = correlationBatch.split(":");

                final String errorMessage =
                        "invalid correlationBatch, must have format <batch number>:<total batch count> " +
                        "where the first batch number is 1";

                if (values.length != 2) {
                    throw new IllegalArgumentException(errorMessage);
                }

                try {
                    this.currentBatchNumber = Integer.parseInt(values[0]);
                    this.totalBatchCount = Integer.parseInt(values[1]);
                } catch (final NumberFormatException nfe) {
                    throw new IllegalArgumentException(errorMessage, nfe);
                }

                if ((currentBatchNumber < 1) || (totalBatchCount < 1) || (totalBatchCount < currentBatchNumber)) {
                    throw new IllegalArgumentException(errorMessage);
                }
            }
        }

        public boolean hasBatchInfo() {
            return currentBatchNumber != null;
        }

        public File getBaseRunDirectory() {

            final String stackPath = Paths.get(rootDirectory, renderWeb.owner, renderWeb.project, stack).toString();

            final Path path;
            if (runName == null) {
                final SimpleDateFormat sdf = new SimpleDateFormat("'run_'yyyyMMdd_hhmmss");
                path = Paths.get(stackPath, sdf.format(new Date()));
            } else {
                path = Paths.get(stackPath, runName);
            }

            return path.toFile().getAbsoluteFile();
        }

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.bounds.validate();
                parameters.deriveBatchInfo();

                LOG.info("runClient: entry, parameters={}", parameters);

                final ZPositionCorrectionClient client = new ZPositionCorrectionClient(parameters);

                if (parameters.solveExisting) {

                    final CrossCorrelationData ccData = client.loadCrossCorrelationDataSets();
                    client.saveZThicknessFile();
                    client.estimateAndSaveZCoordinates(ccData);

                } else {

                    final CrossCorrelationData ccData = client.deriveCrossCorrelationData();
                    if (parameters.hasBatchInfo()) {
                        client.saveCrossCorrelationData(ccData);
                    } else {
                        client.estimateAndSaveZCoordinates(ccData);
                    }

                }
            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final Options inferenceOptions;

    private final File baseRunDirectory;
    private final File runDirectory;
    private final RenderDataClient renderDataClient;
    private final List<SectionData> sectionDataList;
    private final List<Double> sortedZList;
    private final int firstLayerOffset;
    private final List<Double> stackResolutionValues;

    ZPositionCorrectionClient(final Parameters parameters)
            throws IllegalArgumentException, IOException {

        this.parameters = parameters;
        this.baseRunDirectory = parameters.getBaseRunDirectory();
        this.renderDataClient = parameters.renderWeb.getDataClient();

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        this.stackResolutionValues = stackMetaData.getCurrentResolutionValues();

        this.inferenceOptions = parameters.getInferenceOptions();

        this.sectionDataList = renderDataClient.getStackSectionData(parameters.stack,
                                                                    parameters.layerRange.minZ,
                                                                    parameters.layerRange.maxZ);

        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException(
                    "stack " + parameters.stack + " does not contain any layers with the specified z values");
        }

        final List<Double> allSortedZList = sectionDataList.stream()
                .map(SectionData::getZ)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (parameters.hasBatchInfo()) {
            this.sortedZList = getSortedZListForBatch(allSortedZList,
                                                      parameters.currentBatchNumber,
                                                      parameters.totalBatchCount,
                                                      this.inferenceOptions.comparisonRange);
            this.firstLayerOffset = allSortedZList.indexOf(this.sortedZList.get(0));
        } else {
            this.sortedZList = allSortedZList;
            this.firstLayerOffset = 0;
        }

        if ((parameters.runName != null) && (! parameters.solveExisting)) {
            final Double firstZ = sortedZList.get(0);
            final Double lastZ = sortedZList.get(sortedZList.size() - 1);
            final String zRange = String.format("z_%08.1f_to_%08.1f", firstZ, lastZ);
            this.runDirectory = new File(this.baseRunDirectory, zRange);
        } else {
            this.runDirectory = this.baseRunDirectory;
        }

        FileUtil.ensureWritableDirectory(this.runDirectory);
    }

    String getLayerUrlPattern() {

        final RenderWebServiceUrls urls = renderDataClient.getUrls();
        final String stackUrlString = urls.getStackUrlString(parameters.stack);

        final Bounds totalBounds = SectionData.getTotalBounds(sectionDataList);

        final Bounds layerBounds;
        if (parameters.bounds.isDefined()) {
            layerBounds = new Bounds(parameters.bounds.minX, parameters.bounds.minY, totalBounds.getMinZ(),
                                     parameters.bounds.maxX, parameters.bounds.maxY, totalBounds.getMaxZ());
        } else {
            layerBounds = totalBounds;
        }

        return String.format("%s/z/%s/box/%d,%d,%d,%d,%s/render-parameters",
                             stackUrlString, "%s",
                             layerBounds.getX(), layerBounds.getY(),
                             layerBounds.getWidth(), layerBounds.getHeight(),
                             parameters.scale);
    }

    CrossCorrelationData deriveCrossCorrelationData()
            throws IllegalArgumentException, IOException {

        final File runParametersFile = new File(runDirectory, "client-parameters.json");
        JsonUtils.MAPPER.writeValue(runParametersFile, parameters);

        LOG.info("deriveCrossCorrelationData: wrote run parameters to {}", runParametersFile.getAbsolutePath());

        saveZThicknessFile();

        final File inferenceOptionsFile = new File(runDirectory, "inference-options.json");
        JsonUtils.MAPPER.writeValue(inferenceOptionsFile, inferenceOptions);

        LOG.info("deriveCrossCorrelationData: using inference options: {}", inferenceOptions);

        final String layerUrlPattern = getLayerUrlPattern();
        final long pixelsInLargeMask = 20000 * 10000;
        final ImageProcessorCache maskCache = new ImageProcessorCache(pixelsInLargeMask,
                                                                      false,
                                                                      false);
        final RenderLayerLoader layerLoader;
        if (parameters.resinMaskingEnabled)  {
            layerLoader = new MaskedResinLayerLoader(layerUrlPattern,
                                                     sortedZList,
                                                     maskCache,
                                                     parameters.resinSigma,
                                                     parameters.scale,
                                                     parameters.resinContentThreshold,
                                                     parameters.resinMaskIntensity);
        } else {
            layerLoader = new RenderLayerLoader(layerUrlPattern,
                                                sortedZList,
                                                maskCache);
        }

        if (parameters.debugFormat != null) {
            final File debugDirectory = new File(runDirectory, "debug-images");
            FileUtil.ensureWritableDirectory(debugDirectory);
            final String debugFilePattern = debugDirectory.getAbsolutePath() + "/z%08.1f." + parameters.debugFormat;
            layerLoader.setDebugFilePattern(debugFilePattern);
        }

        return HeadlessZPositionCorrection.deriveCrossCorrelationWithCachedLoaders(layerLoader,
                                                                                   inferenceOptions.comparisonRange,
                                                                                   firstLayerOffset);
    }

    void saveZThicknessFile()
            throws IOException {
        final Path layerThicknessPath = Paths.get(runDirectory.getAbsolutePath(), "Zthick.txt");
        if (stackResolutionValues.size() > 2) {
            final String zResolutionString = stackResolutionValues.get(2) + " nm/section\n";
            Files.write(layerThicknessPath, zResolutionString.getBytes(StandardCharsets.UTF_8));
        } else {
            LOG.warn("saveZThicknessFile: stack resolution values are not defined, skipping creation of {}",
                     layerThicknessPath);
        }
    }

    void saveCrossCorrelationData(final CrossCorrelationData ccData)
            throws IOException {
        final String ccDataPath = new File(runDirectory, CC_DATA_FILE_NAME).getAbsolutePath();
        FileUtil.saveJsonFile(ccDataPath, ccData);
    }

    CrossCorrelationData loadCrossCorrelationDataSets()
            throws IllegalArgumentException, IOException {

        final List<CrossCorrelationData> dataSets = new ArrayList<>();
        final int depth = 2;
        try (final Stream<Path> stream = Files.walk(baseRunDirectory.toPath(), depth)) {
            stream.filter(f -> f.getFileName().toString().equals(CC_DATA_FILE_NAME))
                    .forEach(p -> {
                        final CrossCorrelationData ccData = loadCrossCorrelationDataFile(p);
                        dataSets.add(ccData);
                    });
        }
        if (dataSets.size() < 1) {
            throw new IllegalArgumentException("no cross correlation data files found in " +
                                               baseRunDirectory.getAbsolutePath());
        }

        return CrossCorrelationData.merge(dataSets);
    }

    void estimateAndSaveZCoordinates(final CrossCorrelationData ccData)
            throws IllegalArgumentException, IOException {

        final RandomAccessibleInterval<DoubleType> crossCorrelationMatrix = ccData.toMatrix();
        final double[] transforms =
                HeadlessZPositionCorrection.estimateZCoordinates(crossCorrelationMatrix,
                                                                 inferenceOptions,
                                                                 parameters.nLocalEstimates);

        final String outputFilePath = new File(runDirectory, "Zcoords.txt").getAbsolutePath();
        HeadlessZPositionCorrection.writeEstimations(transforms, outputFilePath, sortedZList.get(0));
    }

    protected static List<Double> getSortedZListForBatch(final List<Double> sortedZList,
                                                         final int batchNumber,
                                                         final int batchCount,
                                                         final int comparisonRange) {
        final int totalLayerCount = sortedZList.size();
        final int batchIndex = batchNumber - 1;
        int layersPerBatch = (totalLayerCount / batchCount);
        final int batchesWithExtraLayer = totalLayerCount % batchCount;

        int fromIndex;
        if (batchIndex < batchesWithExtraLayer) {
            layersPerBatch += 1;
            fromIndex = batchIndex * layersPerBatch;
        } else {
            fromIndex = (batchesWithExtraLayer * (layersPerBatch + 1)) +
                        ((batchIndex - batchesWithExtraLayer) * layersPerBatch);
        }

        final int toIndex = fromIndex + layersPerBatch;

        fromIndex = Math.max(0, fromIndex - comparisonRange); // overlap with prior batch by comparison range

        return sortedZList.subList(fromIndex, toIndex);
    }

    private static CrossCorrelationData loadCrossCorrelationDataFile(final Path path) {
        final CrossCorrelationData ccData;
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            ccData = CrossCorrelationData.fromJson(reader);
        } catch (final Exception e) {
            throw new RuntimeException("failed to load data from " + path, e);
        }
        return ccData;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ZPositionCorrectionClient.class);

    private static final String CC_DATA_FILE_NAME = "cc_data.json.gz";
}
