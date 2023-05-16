package org.janelia.render.client.zspacing;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.imglib2.util.StopWatch;

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
                names = "--normalizedEdgeLayerCount",
                description = "The number of layers at the beginning and end of the stack to assign correction " +
                              "delta values of 1.0.  This hack fixes the stretched or squished corrections the " +
                              "solver typically produces for layers at the edges of the stack.  " +
                              "For Z0720_07m stacks, we set this value to 30.  " +
                              "Omit to leave the solver correction values as is.")
        public Integer normalizedEdgeLayerCount;

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

        @Parameter(
                names = "--poorCorrelationThreshold",
                description = "Generate region correlation data for layers that have correlation " +
                              "below this value.  Omit to skip poor region correlation derivation.")
        public Double poorCorrelationThreshold;

        @Parameter(
                names = "--poorCorrelationRegionRows",
                description = "Number of rows ")
        public int correlationRegionRows = 8;

        @Parameter(
                names = "--poorCorrelationRegionColumns",
                description = "Specify to load existing correlation data and solve.")
        public int correlationRegionColumns = 8;

        @Parameter(
                names = "--poorCorrelationCacheGB",
                description = "Number cache gigabytes to allocate for poor correlation region rendering")
        public Long poorCorrelationCacheGB = 2L;

        @Parameter(
                names = "--poorCorrelationLoadExisting",
                description = "Specify to load existing layer correlation data " +
                              "and then generate regional detailed correlation data",
                arity = 0)
        public boolean poorCorrelationLoadExisting;

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
                final SimpleDateFormat sdf = new SimpleDateFormat("'run_'yyyyMMdd_HHmmss");
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

                client.saveRunFiles();

                if (parameters.solveExisting) {

                    final CrossCorrelationData ccData = client.loadCrossCorrelationDataSets();
                    client.estimateAndSaveZCoordinates(ccData);

                } else if (parameters.poorCorrelationLoadExisting) {

                    if (parameters.poorCorrelationThreshold == null) {
                        throw new IllegalArgumentException(
                                "--poorCorrelationThreshold must be specified with --generateRegionalDataForExisting");
                    }
                    final CrossCorrelationData ccData = client.loadCrossCorrelationDataSets();
                    client.savePoorCrossCorrelationData(ccData);

                } else {

                    final CrossCorrelationData ccData = client.deriveCrossCorrelationData();
                    if (parameters.poorCorrelationThreshold != null) {
                        client.savePoorCrossCorrelationData(ccData);
                    }
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
    private final Bounds totalBounds;
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

        final List<SectionData> sectionDataList = renderDataClient.getStackSectionData(parameters.stack,
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

        this.totalBounds = SectionData.getTotalBounds(sectionDataList);

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

        if (parameters.runName == null) {
            this.runDirectory = this.baseRunDirectory;
        } else if (parameters.solveExisting) {
            final SimpleDateFormat sdf = new SimpleDateFormat("'solve_'yyyyMMdd_HHmmss");
            this.runDirectory = new File(this.baseRunDirectory, sdf.format(new Date()));
        } else { // batched cross correlation run
            final Double firstZ = sortedZList.get(0);
            final Double lastZ = sortedZList.get(sortedZList.size() - 1);
            final String zRange = String.format("z_%08.1f_to_%08.1f", firstZ, lastZ);
            final Path path = Paths.get(this.baseRunDirectory.getAbsolutePath(),
                                        CrossCorrelationData.DEFAULT_BATCHES_DIR_NAME,
                                        zRange);
            this.runDirectory = path.toFile();
        }

        FileUtil.ensureWritableDirectory(this.runDirectory);
    }

    String getLayerUrlPattern(final int regionRowIndex,
                              final int regionColumnIndex,
                              final int numberOfRegionRows,
                              final int numberOfRegionColumns) {

        final RenderWebServiceUrls urls = renderDataClient.getUrls();
        final String stackUrlString = urls.getStackUrlString(parameters.stack);

        Bounds layerBounds;
        if (parameters.bounds.isDefined()) {
            layerBounds = new Bounds(parameters.bounds.minX, parameters.bounds.minY, totalBounds.getMinZ(),
                                     parameters.bounds.maxX, parameters.bounds.maxY, totalBounds.getMaxZ());
        } else {
            layerBounds = totalBounds;
        }

        final int totalNumberOfRegions = numberOfRegionRows * numberOfRegionColumns;
        if (totalNumberOfRegions > 1) {
            final double regionWidth = layerBounds.getWidth() / (double) numberOfRegionColumns;
            final double regionMinX = layerBounds.getMinX() + (regionColumnIndex * regionWidth);
            final double regionHeight = layerBounds.getHeight() / (double) numberOfRegionRows;
            final double regionMinY = layerBounds.getMinY() + (regionRowIndex * regionHeight);
            layerBounds = new Bounds(regionMinX,
                                     regionMinY,
                                     regionMinX + regionWidth,
                                     regionMinY + regionHeight);
        }

        return String.format("%s/z/%s/box/%d,%d,%d,%d,%s/render-parameters",
                             stackUrlString, "%s",
                             layerBounds.getX(), layerBounds.getY(),
                             layerBounds.getWidth(), layerBounds.getHeight(),
                             parameters.scale);
    }

    CrossCorrelationData deriveCrossCorrelationData()
            throws IllegalArgumentException {

        LOG.info("deriveCrossCorrelationData: using comparison range: {}", inferenceOptions.comparisonRange);

        final StopWatch stopWatch = StopWatch.createAndStart();

        final String layerUrlPattern = getLayerUrlPattern(0,
                                                          0,
                                                          1,
                                                          1);
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

        final CrossCorrelationData ccData =
                HeadlessZPositionCorrection.deriveCrossCorrelationWithCachedLoaders(layerLoader,
                                                                                    inferenceOptions.comparisonRange,
                                                                                    firstLayerOffset);
        stopWatch.stop();

        LOG.info("deriveCrossCorrelationData: exit, process took {}",
                 StopWatch.secondsToString(stopWatch.seconds()));

        return ccData;
    }

    CrossCorrelationWithNextRegionalData deriveRegionalCrossCorrelationData(final Double pZ,
                                                                            final Double qZ,
                                                                            final double layerCorrelation,
                                                                            final int numberOfRegionRows,
                                                                            final int numberOfRegionColumns)
            throws IllegalArgumentException {

        LOG.info("deriveRegionalCrossCorrelationData: entry, pZ={}, qZ={}", pZ, qZ);

        final StopWatch stopWatch = StopWatch.createAndStart();

        final List<Double> zList = Stream.of(pZ, qZ).sorted().collect(Collectors.toList());
        final String layerUrlPattern = getLayerUrlPattern(0,
                                                          0,
                                                          1,
                                                          1);
        final CrossCorrelationWithNextRegionalData ccRegionalData =
                new CrossCorrelationWithNextRegionalData(pZ,
                                                         qZ,
                                                         layerUrlPattern,
                                                         layerCorrelation,
                                                         numberOfRegionRows,
                                                         numberOfRegionColumns);

        final long maxCachedPixels = parameters.poorCorrelationCacheGB * 1_000_000_000L;
        final ImageProcessorCache imageProcessorCache = new ImageProcessorCache(maxCachedPixels,
                                                                                false,
                                                                                false);
        for (int row = 0; row < numberOfRegionRows; row++) {
            for (int column = 0; column < numberOfRegionColumns; column++) {
                final String regionUrlPattern = getLayerUrlPattern(row,
                                                                   column,
                                                                   numberOfRegionRows,
                                                                   numberOfRegionColumns);
                final RenderLayerLoader layerLoader;
                if (parameters.resinMaskingEnabled) {
                    layerLoader = new MaskedResinLayerLoader(regionUrlPattern,
                                                             zList,
                                                             imageProcessorCache,
                                                             parameters.resinSigma,
                                                             parameters.scale,
                                                             parameters.resinContentThreshold,
                                                             parameters.resinMaskIntensity);
                } else {
                    layerLoader = new RenderLayerLoader(regionUrlPattern,
                                                        zList,
                                                        imageProcessorCache);
                }
                
                final CrossCorrelationData crossCorrelationData =
                        HeadlessZPositionCorrection.deriveCrossCorrelationWithCachedLoaders(layerLoader,
                                                                                            1,
                                                                                            firstLayerOffset);
                ccRegionalData.setValue(row,
                                        column,
                                        crossCorrelationData.getCrossCorrelationValue(0, 0));
            }
        }

        stopWatch.stop();

        LOG.info("deriveRegionalCrossCorrelationData: exit,  pZ={}, qZ={}, process took {}",
                 pZ, qZ, StopWatch.secondsToString(stopWatch.seconds()));

        return ccRegionalData;
    }

    void savePoorCrossCorrelationData(final CrossCorrelationData ccData)
            throws IOException {
        final List<Integer> poorCorrelationIndexList =
                ccData.getPoorCorrelationWithNextIndexes(parameters.poorCorrelationThreshold);
        final List<CrossCorrelationWithNextRegionalData> ccRegionalDataList =
                new ArrayList<>(poorCorrelationIndexList.size());
        for (final Integer zIndex : poorCorrelationIndexList) {
            final double pZ = sortedZList.get(zIndex);
            if (zIndex + 1 < sortedZList.size()) {
                final double qZ = sortedZList.get(zIndex + 1);
                ccRegionalDataList.add(
                        deriveRegionalCrossCorrelationData(pZ,
                                                           qZ,
                                                           ccData.getCrossCorrelationValue(zIndex, 0),
                                                           parameters.correlationRegionRows,
                                                           parameters.correlationRegionColumns));
            }
        }
        final String ccDataPath = new File(runDirectory,
                                           CrossCorrelationWithNextRegionalData.DEFAULT_DATA_FILE_NAME).getAbsolutePath();
        FileUtil.saveJsonFile(ccDataPath, ccRegionalDataList);
    }

    void saveRunFiles()
            throws IOException {

        final File runParametersFile = new File(runDirectory, "client-parameters.json");
        JsonUtils.MAPPER.writeValue(runParametersFile, parameters);
        LOG.info("saveRunFiles: wrote {}", runParametersFile.getAbsolutePath());

        // write inference options and Zthick files when we are not generating batched correlation data
        if (! parameters.hasBatchInfo()) {

            final File inferenceOptionsFile = new File(runDirectory, "inference-options.json");
            JsonUtils.MAPPER.writeValue(inferenceOptionsFile, inferenceOptions);
            LOG.info("saveRunFiles: wrote {}", inferenceOptionsFile.getAbsolutePath());

            final Path layerThicknessPath = Paths.get(runDirectory.getAbsolutePath(), "Zthick.txt");
            if (stackResolutionValues.size() > 2) {
                final String zResolutionString = stackResolutionValues.get(2) + " nm/section\n";
                Files.write(layerThicknessPath, zResolutionString.getBytes(StandardCharsets.UTF_8));
                LOG.info("saveRunFiles: wrote {}", layerThicknessPath);
            } else {
                LOG.warn("saveRunFiles: stack resolution values are not defined, skipping creation of {}",
                         layerThicknessPath);
            }

        }
    }

    void saveCrossCorrelationData(final CrossCorrelationData ccData)
            throws IOException {
        final String ccDataPath = new File(runDirectory,
                                           CrossCorrelationData.DEFAULT_DATA_FILE_NAME).getAbsolutePath();
        FileUtil.saveJsonFile(ccDataPath, ccData);
    }

    CrossCorrelationData loadCrossCorrelationDataSets()
            throws IllegalArgumentException, IOException {
        final File ccDataParent = new File(baseRunDirectory, CrossCorrelationData.DEFAULT_BATCHES_DIR_NAME);
        final List<CrossCorrelationData> dataSets =
                CrossCorrelationData.loadCrossCorrelationDataFiles(ccDataParent.toPath(),
                                                                   CrossCorrelationData.DEFAULT_DATA_FILE_NAME,
                                                                   2);
        return CrossCorrelationData.merge(dataSets);
    }

    void estimateAndSaveZCoordinates(final CrossCorrelationData ccData)
            throws IllegalArgumentException, IOException {

        LOG.info("estimateAndSaveZCoordinates: using inference options: {}", inferenceOptions);

        final StopWatch stopWatch = StopWatch.createAndStart();

        final RandomAccessibleInterval<DoubleType> crossCorrelationMatrix = ccData.toMatrix();

        double[] transforms = HeadlessZPositionCorrection.estimateZCoordinates(crossCorrelationMatrix,
                                                                               inferenceOptions,
                                                                               parameters.nLocalEstimates);

        if ((parameters.normalizedEdgeLayerCount != null) &&
            (transforms.length > (3 * parameters.normalizedEdgeLayerCount))) {
            transforms = normalizeTransforms(transforms, parameters.normalizedEdgeLayerCount);
        }
        
        final String outputFilePath = new File(runDirectory, "Zcoords.txt").getAbsolutePath();
        HeadlessZPositionCorrection.writeEstimations(transforms, outputFilePath, sortedZList.get(0));

        stopWatch.stop();

        LOG.info("estimateAndSaveZCoordinates: exit, process took {}",
                 StopWatch.secondsToString(stopWatch.seconds()));
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

    public static double[] normalizeTransforms(final double[] transforms,
                                               final int normalizedEdgeLayerCount) {

        // compute the median of all deltas
        final double[] sortedDeltas = new double[transforms.length - 1];
        for (int i = 1; i < transforms.length; i++) {
            sortedDeltas[i-1] = transforms[i] - transforms[i - 1];
        }
        Arrays.sort(sortedDeltas);
        final double closeEnoughMedian = sortedDeltas[sortedDeltas.length / 2]; // this should be very close to 1.0

        final double problemMargin = closeEnoughMedian / 5; // so this is roughly 0.8-1.2
        final double minDelta = closeEnoughMedian - problemMargin;
        final double maxDelta = closeEnoughMedian + problemMargin;

        final double[] normalizedTransforms = new double[transforms.length];
        normalizedTransforms[0] = transforms[0];

        final double overrideDelta = 1.0;
        final int firstEndIndex = transforms.length - normalizedEdgeLayerCount;

        for (int i = 1; i < transforms.length; i++) {
            double delta = transforms[i] - transforms[i - 1];
            if ((i < normalizedEdgeLayerCount) || (i > firstEndIndex)) {
                if ((delta < minDelta) || (delta > maxDelta)) {
                    LOG.info("normalizeTransforms: reset transform[{}] delta from {} to {}",
                             i, delta, overrideDelta);
                    delta = overrideDelta;
                }
            }
            normalizedTransforms[i] = normalizedTransforms[i-1] + delta; // do we have to worry about accuracy? No.
        }

        // if normalization changed last z (almost always the case), scale back to the same global range
        final int lastTransformIndex = transforms.length - 1;
        if (normalizedTransforms[lastTransformIndex] != transforms[lastTransformIndex]) {
            final double min = transforms[0];
            final double max = transforms[transforms.length - 1];
            final double minN = normalizedTransforms[0];
            final double maxN = normalizedTransforms[normalizedTransforms.length - 1];

            // e.g. original: 1-1000, fixed: 1.1-995.23
            final double scale = (max - min) / (maxN - minN); // this should not be far from 1.0

            LOG.info("normalizeTransforms: scaling normalized range ({} to {}) back to original range ({} to {}) with scale {}",
                     minN, maxN, min, max, scale);

            for (int i = 0; i < transforms.length; ++i) {
                // e.g. (995.23 - 1.1) * (999/994.13) + 1.0
                normalizedTransforms[i] = ((normalizedTransforms[i] - minN) * scale) + min;
            }

            // reset ends to avoid rounding issues
            normalizedTransforms[0] = transforms[0];
            normalizedTransforms[normalizedTransforms.length - 1] = transforms[normalizedTransforms.length - 1];

            LOG.info("normalizeTransforms: reset ends to {} and {}",
                     normalizedTransforms[0], normalizedTransforms[normalizedTransforms.length - 1]);
        }

        return normalizedTransforms;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ZPositionCorrectionClient.class);
}
