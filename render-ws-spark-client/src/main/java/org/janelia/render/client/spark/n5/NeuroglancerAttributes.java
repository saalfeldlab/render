package org.janelia.render.client.spark.n5;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;

/**
 * Attributes used by neuroglancer to display n5 volumes.
 *
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

    /** Attribute key for "intermediate" directory json files required by neuroglancer. */
    public static final String SUPPORTED_KEY = "neuroglancer_supported";

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    public static class PixelResolution {

        private final List<Double> dimensions;
        private final String unit;

        public PixelResolution(final List<Double> dimensions,
                               final String unit) {
            this.dimensions = dimensions;
            this.unit = unit;
        }
    }

    private final List<String> axes;
    private final List<String> units;
    private final List<List<Integer>> scales;
    private final PixelResolution pixelResolution;

    /**
     * @param  stackResolutionValues        stack resolution values for each axis (x, y, z).
     * @param  stackResolutionUnit          units for stack resolution values.  May be "m" (for meters),
     *                                      "s" (for seconds), or "Hz" (Hertz) with any SI prefix.
     *                                      May be an empty string to indicate a unit-less dimension.
     * @param  numberOfDownsampledDatasets  number of downsampled datasets (for multi-scaled datasets).
     * @param  downSampleFactors            downsample factors (for multi-scaled datasets).
     */
    public NeuroglancerAttributes(final List<Double> stackResolutionValues,
                                  final String stackResolutionUnit,
                                  final int numberOfDownsampledDatasets,
                                  final int[] downSampleFactors) {

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

        this.pixelResolution = new PixelResolution(stackResolutionValues,
                                                   stackResolutionUnit);
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
        final Path ngAttributesPath = fullScaleDatasetPath.endsWith("s0") ?
                                      fullScaleDatasetPath.getParent() : fullScaleDatasetPath;

        final N5Writer n5Writer = new N5FSWriter(n5BasePath.toAbsolutePath().toString());

        // Neuroglancer recursively looks for attribute.json files from root path and stops at
        // the first subdirectory without an attributes.json file.
        // See https://github.com/google/neuroglancer/blob/c3c8af92cf539773e3ffdcb39d1268a0e0c54a6d/src/neuroglancer/datasource/n5/frontend.ts#L181-L194

        // For more complex projects with hierarchical data sets (e.g. /render/<stack>, /z_corr/<stack>, ...),
        // we need to ensure attributes.json files exist in each subdirectory.
        for (final Path pathElement : ngAttributesPath.getParent()) {
            n5Writer.setAttribute(pathElement.toString(), SUPPORTED_KEY, true);
        }

        // Finally, write the neuroglancer attributes.
        // See https://github.com/google/neuroglancer/blob/master/src/neuroglancer/datasource/n5/README.md
        final Map<String, Object> attributes = new HashMap<>();
        attributes.put("axes", axes);
        attributes.put("units", units);
        attributes.put("scales", scales);
        attributes.put("pixelResolution", pixelResolution);

        n5Writer.setAttributes(ngAttributesPath.toString(), attributes);
    }

}
