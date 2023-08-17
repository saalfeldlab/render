package org.janelia.alignment.util;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attributes used by neuroglancer to display n5 volumes.
 * For details, see
 * <a href="https://github.com/google/neuroglancer/blob/master/src/neuroglancer/datasource/n5/README.md">
 *   neuroglancer n5 datasource
 * </a>.
 *
 * @author Eric Trautman
 */
public class NeuroglancerAttributes {

    /** Axis list for all render stacks. */
    public static final List<String> RENDER_AXES = Arrays.asList("x", "y", "z");
    public static final List<String> RENDER_AXES_2D = RENDER_AXES.subList(0, 2);

    /** Attribute key for "intermediate" directory json files required by neuroglancer. */
    public static final String SUPPORTED_KEY = "neuroglancer_supported";

    @SuppressWarnings({"FieldCanBeLocal", "unused", "MismatchedQueryAndUpdateOfCollection"})
    public static class PixelResolution {

        private final List<Double> dimensions;
        private final String unit;

        public PixelResolution(final List<Double> dimensions,
                               final String unit) {
            this.dimensions = new ArrayList<>(dimensions);
            this.unit = unit;
        }
    }

    public enum NumpyContiguousOrdering {

        /**
         * C contiguous arrays have rows stored as contiguous blocks of memory.
         * Axes z, y, x => C ordering.
         */
        C("C"),

        /**
         * Fortran contiguous arrays have columns stored as contiguous blocks of memory.
         * Axes x, y, z => F ordering.
         */
        FORTRAN("F"),

        /** Any contiguous order indicates the array may be in any order (C, F, or even not contiguous). */
        ANY("A");

        private final String code;
        NumpyContiguousOrdering(final String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final List<String> axes;
    private final NumpyContiguousOrdering contiguousOrdering;
    private final List<String> units;
    private final List<List<Integer>> scales;
    private final PixelResolution pixelResolution;
    private final List<Long> translate;

    /**
     * @param  stackResolutionValues        stack resolution values for each axis (x, y, z).
     * @param  stackResolutionUnit          units for stack resolution values.  May be "m" (for meters),
     *                                      "s" (for seconds), or "Hz" (Hertz) with any SI prefix.
     *                                      May be an empty string to indicate a unit-less dimension.
     * @param  numberOfDownsampledDatasets  number of downsampled datasets (for multi-scaled datasets).
     * @param  downSampleFactors            downsample factors (for multi-scaled datasets).
     * @param  translate                    translation or offset required to map n5 origin to render stack origin.
     * @param  contiguousOrdering           numpy contiguous ordering.
     */
    public NeuroglancerAttributes(final List<Double> stackResolutionValues,
                                  final String stackResolutionUnit,
                                  final int numberOfDownsampledDatasets,
                                  final int[] downSampleFactors,
                                  final List<Long> translate,
                                  final NumpyContiguousOrdering contiguousOrdering) {

        this.contiguousOrdering = contiguousOrdering;

        if (stackResolutionValues.size() == 3) {
            this.axes = RENDER_AXES;
            this.units = Arrays.asList(stackResolutionUnit, stackResolutionUnit, stackResolutionUnit);

            this.scales = new ArrayList<>();
            this.scales.add(Arrays.asList(1, 1, 1));
            for (int i = 0; i < numberOfDownsampledDatasets; i++) {
                final int xScale = (int) Math.pow(downSampleFactors[0], i + 1);
                final int yScale = (int) Math.pow(downSampleFactors[1], i + 1);
                final int zScale = (int) Math.pow(downSampleFactors[2], i + 1);
                this.scales.add(Arrays.asList(xScale, yScale, zScale));
            }
        } else if (stackResolutionValues.size() == 2) {
            this.axes = RENDER_AXES_2D;
            this.units = Arrays.asList(stackResolutionUnit, stackResolutionUnit);

            this.scales = new ArrayList<>();
            this.scales.add(Arrays.asList(1, 1));
            for (int i = 0; i < numberOfDownsampledDatasets; i++) {
                final int xScale = (int) Math.pow(downSampleFactors[0], i + 1);
                final int yScale = (int) Math.pow(downSampleFactors[1], i + 1);
                this.scales.add(Arrays.asList(xScale, yScale));
            }
        } else {
            throw new IllegalArgumentException("stackResolutionValues size is " + stackResolutionValues.size() +
                                               " but only 2D and 3D volumes are currently supported");
        }

        this.pixelResolution = new PixelResolution(stackResolutionValues,
                                                   stackResolutionUnit);

        this.translate = new ArrayList<>(translate);
    }

    /**
     * Writes all attribute.json files required by neuroglancer to display the specified dataset.
     *
     * @param  n5BasePath            base path for n5.
     * @param  fullScaleDatasetPath  path of the full scale data set.
     *
     * @throws IOException
     *   if the writes fail for any reason.
     */
    public void write(final Path n5BasePath,
                      final Path fullScaleDatasetPath)
            throws IOException {

        // for multi-scale datasets, ng attributes need to go in s0 parent
        final boolean isMultiScaleDataset = fullScaleDatasetPath.endsWith("s0");
        final Path ngAttributesPath = isMultiScaleDataset ?
                                      fullScaleDatasetPath.getParent() : fullScaleDatasetPath;

        LOG.info("write: entry, n5BasePath={}, fullScaleDatasetPath={}, ngAttributesPath={}",
                 n5BasePath, fullScaleDatasetPath, ngAttributesPath);

        final N5Writer n5Writer = new N5FSWriter(n5BasePath.toAbsolutePath().toString());

        // Neuroglancer recursively looks for attribute.json files from root path and stops at
        // the first subdirectory without an attributes.json file.
        // See https://github.com/google/neuroglancer/blob/c3c8af92cf539773e3ffdcb39d1268a0e0c54a6d/src/neuroglancer/datasource/n5/frontend.ts#L181-L194

        // For more complex projects with hierarchical data sets (e.g. /render/<stack>, /z_corr/<stack>, ...),
        // we need to ensure attributes.json files exist in each subdirectory.
        for (Path path = ngAttributesPath.getParent();
             (path != null) && (! path.endsWith("/"));
             path = path.getParent()) {
            LOG.info("write: saving supported attribute to {}{}/attributes.json", n5BasePath, path);
            n5Writer.setAttribute(path.toString(), SUPPORTED_KEY, true);
        }

        // Finally, write the neuroglancer attributes.
        // See https://github.com/google/neuroglancer/blob/master/src/neuroglancer/datasource/n5/README.md
        final Map<String, Object> attributes = new HashMap<>();
        attributes.put("axes", axes);
        if (contiguousOrdering != null) {
            attributes.put("ordering", contiguousOrdering.getCode());
        }
        attributes.put("units", units);
        attributes.put("scales", scales);
        attributes.put("pixelResolution", pixelResolution);
        attributes.put("translate", translate);

        LOG.info("write: saving neuroglancer attributes to {}{}/attributes.json", n5BasePath, ngAttributesPath);
        n5Writer.setAttributes(ngAttributesPath.toString(), attributes);

        if (isMultiScaleDataset) {
            for (int scaleLevel = 0; scaleLevel < scales.size(); scaleLevel++) {
                writeScaleLevelTransformAttributes(scaleLevel,
                                                   scales.get(scaleLevel),
                                                   n5Writer,
                                                   n5BasePath,
                                                   ngAttributesPath);
            }
        }
    }

    private void writeScaleLevelTransformAttributes(final int scaleLevel,
                                                    final List<Integer> scaleLevelFactors,
                                                    final N5Writer n5Writer,
                                                    final Path n5BasePath,
                                                    final Path ngAttributesPath)
            throws IOException {

        final String scaleName = "s" + scaleLevel;
        final Path scaleAttributesPath = Paths.get(ngAttributesPath.toString(), scaleName);

        final Path scaleLevelDirectoryPath = Paths.get(n5BasePath.toString(), ngAttributesPath.toString(), scaleName);
        if (! scaleLevelDirectoryPath.toFile().exists()) {
            throw new IOException(scaleLevelDirectoryPath.toAbsolutePath() + " does not exist");
        }

        final Map<String, Object> transformAttributes = new HashMap<>();
        transformAttributes.put("axes", axes);
        transformAttributes.put("ordering", contiguousOrdering.getCode());
        transformAttributes.put("units", units);

        final List<Double> groupDimensions  = pixelResolution.dimensions;
        final List<Double> scaleList = new ArrayList<>();
        final List<Double> translateList = new ArrayList<>();
        for (int dimensionIndex = 0; dimensionIndex < scaleLevelFactors.size(); dimensionIndex++) {

            final int factor = scaleLevelFactors.get(dimensionIndex);
            scaleList.add(factor * groupDimensions.get(dimensionIndex));

            final double unscaledTranslation = (factor - 1) / 2.0;
            translateList.add(unscaledTranslation * groupDimensions.get(dimensionIndex));
        }

        transformAttributes.put("scale", scaleList);
        transformAttributes.put("translate", translateList);

        final Map<String, Object> attributes = new HashMap<>();
        attributes.put("transform", transformAttributes);

        LOG.info("writeScaleLevelTransformAttributes: saving {}{}/attributes.json", n5BasePath, scaleAttributesPath);
        n5Writer.setAttributes(scaleAttributesPath.toString(), attributes);
    }

    private static final Logger LOG = LoggerFactory.getLogger(NeuroglancerAttributes.class);
}
