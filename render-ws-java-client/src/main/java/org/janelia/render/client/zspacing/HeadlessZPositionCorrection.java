package org.janelia.render.client.zspacing;

import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.zspacing.loader.LayerLoader;
import org.janelia.render.client.zspacing.loader.LayerLoader.FloatProcessors;
import org.janelia.render.client.zspacing.loader.PathLayerLoader;
import org.janelia.render.client.zspacing.loader.SimpleLeastRecentlyUsedLayerCache;
import org.janelia.thickness.inference.InferFromMatrix;
import org.janelia.thickness.inference.Options;
import org.janelia.thickness.inference.fits.AbstractCorrelationFit;
import org.janelia.thickness.inference.fits.GlobalCorrelationFitAverage;
import org.janelia.thickness.inference.fits.LocalCorrelationFitAverage;
import org.janelia.thickness.inference.visitor.Visitor;
import org.janelia.thickness.plugin.RealSumFloatNCC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.StopWatch;

/**
 * Adapter to run {@link InferFromMatrix#estimateZCoordinates} in "headless" mode without any visualization.
 * Core logic was copied from {@link org.janelia.thickness.plugin.ZPositionCorrection#run}.
 *
 * @author Eric Trautman
 */
public class HeadlessZPositionCorrection {

    /**
     * @param  crossCorrelationMatrix  matrix of pixels representing cross correlation similarity between each
     *                                 layer and its neighbors (see {@link #deriveCrossCorrelation}).
     * @param  inferenceOptions        options for estimation.
     * @param  nLocalEstimates         number of local estimates used to derive estimateWindowRadius option value.
     *
     * @return estimated z coordinates for each specified layer.
     *
     * @throws IllegalArgumentException
     *   if there are too few or too many layers to process.
     *
     * @throws RuntimeException
     *   if any errors occur during estimation.
     */
    public static double[] estimateZCoordinates(final RandomAccessibleInterval<DoubleType> crossCorrelationMatrix,
                                                final Options inferenceOptions,
                                                final int nLocalEstimates)
            throws IllegalArgumentException, RuntimeException {

        final StopWatch stopWatch = StopWatch.createAndStart();

        final int nLayers = (int) crossCorrelationMatrix.dimension(0);

        LOG.debug("estimateZCoordinates: entry, estimating for {} layers", nLayers);

        // clone inferenceOptions since we need to override/derive some values
        final Options options = inferenceOptions.clone();

        options.estimateWindowRadius = nLayers / nLocalEstimates;

        // override comparisonRange if we only have a small number of layers
        if (nLayers < options.comparisonRange) {
            options.comparisonRange = nLayers;
        }

        final double[] startingCoordinates = new double[nLayers];
        for (int i = 0; i < startingCoordinates.length; i++) {
            startingCoordinates[i] = i;
        }

        final AbstractCorrelationFit correlationFit =
                nLocalEstimates < 2 ?
                new GlobalCorrelationFitAverage() :
                new LocalCorrelationFitAverage(startingCoordinates.length, options);

        final InferFromMatrix inf = new InferFromMatrix(correlationFit);

        final double[] transform;
        try {
            final Visitor visitor = new LoggingVisitor(); // simply logs progress and does nothing else
            //final Visitor visitor = new LUTVisitor("", "", "," );
            //final Visitor visitor = new ScalingFactorsVisitor("", "", "," );
            transform = inf.estimateZCoordinates(crossCorrelationMatrix, startingCoordinates, visitor, options);
        } catch (final Exception e) {
            throw new RuntimeException("failed to estimate z coordinates", e);
        }

        stopWatch.stop();

        LOG.debug("estimateZCoordinates: exit, took {}", stopWatch);

        return transform;
    }

    /**
     * @param  layerLoader       loader for each layer's pixels (this gets wrapped in a caching loader).
     * @param  comparisonRange   number of adjacent neighbor layers to compare with each layer.
     * @param  firstLayerOffset  offset of the first layer loaded relative to the full set of layers.
     *
     * @return cross correlation similarity between each layer and its neighbors.
     *
     * @throws IllegalArgumentException
     *   if there are too few or too many layers to process.
     */
    public static CrossCorrelationData deriveCrossCorrelationWithCachedLoaders(final LayerLoader layerLoader,
                                                                               final int comparisonRange,
                                                                               final int firstLayerOffset)
            throws IllegalArgumentException {

        final int maxLayersToCache = comparisonRange + 1;
        final LayerLoader cachedLayerLoader = new SimpleLeastRecentlyUsedLayerCache(layerLoader,
                                                                                    maxLayersToCache);
        return deriveCrossCorrelation(cachedLayerLoader,
                                      comparisonRange,
                                      firstLayerOffset);
    }

    /**
     * @param  layerLoader       loader for z-ordered layers to process.
     * @param  comparisonRange   number of adjacent neighbor layers to compare with each layer.
     * @param  firstLayerOffset  offset of the first layer loaded relative to the full set of layers.
     *
     * @return cross correlation similarity between each layer and its neighbors.
     *
     * @throws IllegalArgumentException
     *   if there are too few or too many layers to process.
     */
    public static CrossCorrelationData deriveCrossCorrelation(final LayerLoader layerLoader,
                                                              final int comparisonRange,
                                                              final int firstLayerOffset)
            throws IllegalArgumentException {

        final int layerCount = layerLoader.getNumberOfLayers();
        if (layerCount < 2) {
            throw new IllegalArgumentException("must have at least two layers to evaluate");
        }

        LOG.info("building cross correlation data for {} layers", layerLoader.getNumberOfLayers());

        final CrossCorrelationData ccData = new CrossCorrelationData(layerCount,
                                                                     comparisonRange,
                                                                     firstLayerOffset);

        for (int fromLayerIndex = 0; fromLayerIndex < layerCount; ++fromLayerIndex) {

            final FloatProcessors processorsA = layerLoader.getProcessors(fromLayerIndex);
            final float[] pixelsA = (float[]) processorsA.image.getPixels();
            final float[] masksA = processorsA.mask == null ? null : (float[]) processorsA.mask.getPixels();

            for (int toLayerIndex = fromLayerIndex + 1;
                 toLayerIndex - fromLayerIndex <= comparisonRange && toLayerIndex < layerCount;
                 ++toLayerIndex) {

                final FloatProcessors processorsB = layerLoader.getProcessors(toLayerIndex);
                final float[] pixelsB = (float[]) processorsB.image.getPixels();
                final float[] masksB = processorsB.mask == null ? null : (float[]) processorsB.mask.getPixels();

                if (pixelsA.length != pixelsB.length) {
                    throw new IllegalArgumentException(buildSizeErrorMessage(processorsA, processorsB, pixelsA, pixelsB));
                }

                final float val;
                if ((masksA == null) || (masksB == null)) {
                    val = new RealSumFloatNCC(pixelsA, pixelsB).call().floatValue();
                } else {
                    val = new RealSumFloatNCCMasks(pixelsA, masksA, pixelsB, masksB).call().floatValue();
                }

                ccData.set(fromLayerIndex, toLayerIndex, val);
            }
        }

        return ccData;
    }

    /**
     * Writes thickness estimations to the specified file.
     *
     * @param  transforms  estimation results.
     * @param  path        path of output file (specify null to write to stdout).
     * @param  zOffset     z value for first layer (used to offset transform values)
     *
     * @throws FileNotFoundException
     *   if the file cannot be written.
     */
    public static void writeEstimations(final double[] transforms,
                                        final String path,
                                        final double zOffset)
            throws FileNotFoundException {

        if (path != null) {
            LOG.info("writing estimations to {}", path);
        }

        // use integral offset if possible to replicate legacy tool output which assumed integral z values
        Integer integralOffset = null;
        if ((zOffset == Math.floor(zOffset)) && ! Double.isInfinite(zOffset)) {
            integralOffset = (int) zOffset;
        }

        try (final PrintWriter writer = path == null ? new PrintWriter(System.out) : new PrintWriter(path)) {
            for (int i = 0; i < transforms.length; i++) {
                final Object originalZ;
                if (integralOffset == null) {
                    originalZ = zOffset + i;
                } else {
                    originalZ = integralOffset + i;
                }
                final double transformedZ = zOffset + transforms[i];
                writer.println(originalZ + " " + transformedZ);
            }
        }

    }

    /**
     * For option descriptions, go to
     * <a href="https://academic.oup.com/bioinformatics/article/33/9/1379/2736362">
     *   this paper
     * </a>, click the dynamically generated "Supplementary data" link, and then scroll to page 7.
     *
     * @return default options for our FIBSEM cases.
     */
    public static Options generateDefaultFIBSEMOptions() {
        final Options options = Options.generateDefaultOptions();

        // values from 2016 paper supplement that match code defaults but are listed here for completeness:
        options.comparisonRange = 10;
        options.nIterations = 100;
        options.shiftProportion = 0.6;
        options.scalingFactorEstimationIterations = 10;
        options.coordinateUpdateRegularizerWeight = 0.0;
        options.regularizationType = InferFromMatrix.RegularizationType.BORDER;
        options.forceMonotonicity = false;
        options.estimateWindowRadius = -1;            // note: always overridden by code

        // changes:
        options.withReorder = false;                  // milled data should always be correctly ordered
        options.scalingFactorRegularizerWeight = 1.0; // won't correct for noisy slices
        options.minimumSectionThickness = 0.0001;

        return options;
    }

    public static class MainParameters extends CommandLineParameters {

        @Parameter(
                names = "--imagePaths",
                description = "layer image files or directories to process, assumption is that sorted base names match z order",
                variableArity = true,
                required = true)
        public List<String> imagePaths;

        @Parameter(
                names = "--extensions",
                description = "Image file extensions to look for within specified directories (default is 'png' and 'jpg')",
                variableArity = true)
        public List<String> extensionsList;

        @Parameter(
                names = "--optionsJson",
                description = "JSON file containing thickness correction options (omit to use default values)")
        public String optionsJson;

        @Parameter(
                names = "--outputFile",
                description = "File for thickness correction results (omit to write results to stdout)")
        public String outputFile;

        @Parameter(
                names = "--nLocalEstimates",
                description = "Number of local estimates")
        public Integer nLocalEstimates = 1;

        @Parameter(
                names = "--zOffset",
                description = "Offset to add to layer z values in output (omit to just start at 0)")
        public Double zOffset;

        public MainParameters() {
        }

        public List<String> getImagePathsSortedByBaseName() {

            final String[] extensions =
                    extensionsList == null ? new String[] { "png", "jpg", "tif" } : extensionsList.toArray(new String[]{});

            final Map<String, File> baseNameToFile = new HashMap<>();

            imagePaths.forEach(imagePath -> {
                final File file = new File(imagePath);
                if (file.isDirectory()) {
                    FileUtils.listFiles(file, extensions, false)
                            .forEach(f -> baseNameToFile.put(f.getName(), f));
                } else {
                    baseNameToFile.put(file.getName(), file);
                }
            });

            return baseNameToFile.keySet().stream()
                    .sorted()
                    .map(baseName -> baseNameToFile.get(baseName).getAbsolutePath())
                    .collect(Collectors.toList());
        }

        public Options getInferenceOptions()
                throws FileNotFoundException {
            return optionsJson == null ? generateDefaultFIBSEMOptions() : Options.read(optionsJson);
        }

    }

    private static String buildSizeErrorMessage(final FloatProcessors processorsA,
                                                final FloatProcessors processorsB,
                                                final float[] pixelsA,
                                                final float[] pixelsB) {
        String renderParametersA = null;
        String renderParametersB = null;
        try {
            renderParametersA = processorsA.getRenderParameters().toJson();
            renderParametersB = processorsB.getRenderParameters().toJson();
        } catch (final JsonProcessingException e) {
            LOG.error("failed to serialize render parameters", e);
        }
        return "mismatched image sizes (" +
               pixelsA.length + " vs. " + pixelsB.length +
               "), A: " + processorsA.image +
               ", B: " + processorsB.image +
               ", A parameters: " + renderParametersA +
               ", B parameters: " + renderParametersB;
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final MainParameters parameters = new MainParameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final Options inferenceOptions = parameters.getInferenceOptions();

                LOG.info("inference options: {}", inferenceOptions);

                final List<String> layerPaths = parameters.getImagePathsSortedByBaseName();

                if (layerPaths.size() > 100) {
                    LOG.warn("processing is single threaded so this might take a while");
                } else if (layerPaths.size() == 0) {
                    throw new IllegalArgumentException("no layer images found in " + parameters.imagePaths);
                }

                final LayerLoader pathLayerLoader = new PathLayerLoader(layerPaths);

                final RandomAccessibleInterval<DoubleType> crossCorrelationMatrix =
                        deriveCrossCorrelationWithCachedLoaders(pathLayerLoader,
                                                                inferenceOptions.comparisonRange,
                                                                0).toMatrix();

                final double[] transforms = estimateZCoordinates(crossCorrelationMatrix,
                                                                 inferenceOptions,
                                                                 parameters.nLocalEstimates);

                writeEstimations(transforms, parameters.outputFile, parameters.zOffset);
            }
        };

        clientRunner.run();

    }

    private static final Logger LOG = LoggerFactory.getLogger(HeadlessZPositionCorrection.class);
}
