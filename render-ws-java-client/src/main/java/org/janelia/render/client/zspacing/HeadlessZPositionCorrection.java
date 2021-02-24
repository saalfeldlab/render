package org.janelia.render.client.zspacing;

import com.beust.jcommander.Parameter;

import ij.ImagePlus;
import ij.process.FloatProcessor;

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
import org.janelia.thickness.inference.InferFromMatrix;
import org.janelia.thickness.inference.Options;
import org.janelia.thickness.inference.fits.AbstractCorrelationFit;
import org.janelia.thickness.inference.fits.GlobalCorrelationFitAverage;
import org.janelia.thickness.inference.fits.LocalCorrelationFitAverage;
import org.janelia.thickness.inference.visitor.LazyVisitor;
import org.janelia.thickness.inference.visitor.Visitor;
import org.janelia.thickness.plugin.RealSumFloatNCC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.DoubleType;

import static org.janelia.thickness.plugin.ZPositionCorrection.createEmptyMatrix;
import static org.janelia.thickness.plugin.ZPositionCorrection.wrapDouble;

/**
 * Adapter to run {@link InferFromMatrix#estimateZCoordinates} in "headless" mode without any visualization.
 * Core logic was copied from {@link org.janelia.thickness.plugin.ZPositionCorrection#run}.
 *
 * @author Eric Trautman
 */
public class HeadlessZPositionCorrection {

    /**
     *
     * @param  inferenceOptions  options for estimation.
     * @param  nLocalEstimates   number of local estimates used to derive estimateWindowRadius option value.
     * @param  layerLoader       loader for each layer's pixels (this gets wrapped in a caching loader).
     *
     * @return estimated z coordinates for each specified layer.
     *
     * @throws IllegalArgumentException
     *   if there are too few or too many layers to process.
     *
     * @throws RuntimeException
     *   if any errors occur during estimation.
     */
    public static double[] buildMatrixAndEstimateZCoordinates(final Options inferenceOptions,
                                                              final int nLocalEstimates,
                                                              final LayerLoader layerLoader)
            throws IllegalArgumentException, RuntimeException {

        final int maxLayersToCache = inferenceOptions.comparisonRange + 1;

        final LayerLoader cachedLayerLoader = new LayerLoader.SimpleLeastRecentlyUsedLayerCache(layerLoader,
                                                                                                maxLayersToCache);

        LOG.info("building cross correlation matrix for {} layers", layerLoader.getNumberOfLayers());

        final RandomAccessibleInterval<DoubleType> crossCorrelationMatrix =
                buildNCCMatrix(cachedLayerLoader,
                               inferenceOptions.comparisonRange);

        LOG.info("estimating Z coordinates for " + layerLoader.getNumberOfLayers() + " layers:");

        return estimateZCoordinates(crossCorrelationMatrix,
                                    inferenceOptions,
                                    nLocalEstimates);
    }

    /**
     * @param  crossCorrelationMatrix  matrix of pixels representing cross correlation similarity between each
     *                                 layer and its neighbors (see {@link #buildNCCMatrix}).
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

        final int nLayers = (int) crossCorrelationMatrix.dimension(0);

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
            final Visitor visitor = new LazyVisitor(); // always use do-nothing visitor
            transform = inf.estimateZCoordinates(crossCorrelationMatrix, startingCoordinates, visitor, options);
        } catch (final Exception e) {
            throw new RuntimeException("failed to estimate z coordinates", e);
        }

        return transform;
    }

    /**
     * @param  layerLoader      loader for z-ordered layers to process.
     * @param  comparisonRange  number of adjacent neighbor layers to compare with each layer.
     *
     * @return matrix of pixels representing cross correlation similarity between each layer and its neighbors.
     *
     * @throws IllegalArgumentException
     *   if there are too few or too many layers to process.
     */
    public static RandomAccessibleInterval<DoubleType> buildNCCMatrix(final LayerLoader layerLoader,
                                                                      final int comparisonRange)
            throws IllegalArgumentException {

        final int layerCount = layerLoader.getNumberOfLayers();
        if (layerCount < 2) {
            throw new IllegalArgumentException("must have at least two layers to evaluate");
        }

        // TODO: replace square matrix image with layerCount x comparisonRange array (since we are not visualizing)
        final long matrixPixels = (long) layerCount * layerCount;
        if (matrixPixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("too many layers (" + layerCount + ") for single matrix");
        }

        final FloatProcessor matrixFp = createEmptyMatrix(layerCount);

        for (int fromLayerIndex = 0; fromLayerIndex < layerCount; ++fromLayerIndex) {

            final float[] pixelsA = (float[]) layerLoader.getProcessor(fromLayerIndex).getPixels();

            for (int toLayerIndex = fromLayerIndex + 1;
                 toLayerIndex - fromLayerIndex <= comparisonRange && toLayerIndex < layerCount;
                 ++toLayerIndex) {

                final float[] pixelsB = (float[]) layerLoader.getProcessor(toLayerIndex).getPixels();
                final float val = new RealSumFloatNCC(pixelsA, pixelsB).call().floatValue();
                matrixFp.setf(fromLayerIndex, toLayerIndex, val);
                matrixFp.setf(toLayerIndex, fromLayerIndex, val);
            }
        }

        // note: removed stripToMatrix logic for non-square matrices since currently, matrix is always square

        return wrapDouble(new ImagePlus("", matrixFp));
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
        options.scalingFactorRegularizerWeight = 0.1;
        options.minimumSectionThickness = 0.01;
        options.coordinateUpdateRegularizerWeight = 0.0;
        options.regularizationType = InferFromMatrix.RegularizationType.BORDER;
        options.forceMonotonicity = false;
        options.estimateWindowRadius = -1;       // note: always overridden by code

        // changes:
        options.withReorder = false;             // milled data should always be correctly ordered

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

                final LayerLoader pathLayerLoader = new LayerLoader.PathLayerLoader(layerPaths);
                final double[] transforms = buildMatrixAndEstimateZCoordinates(inferenceOptions,
                                                                               parameters.nLocalEstimates,
                                                                               pathLayerLoader);

                writeEstimations(transforms, parameters.outputFile, parameters.zOffset);
            }
        };

        clientRunner.run();

    }

    private static final Logger LOG = LoggerFactory.getLogger(HeadlessZPositionCorrection.class);
}
