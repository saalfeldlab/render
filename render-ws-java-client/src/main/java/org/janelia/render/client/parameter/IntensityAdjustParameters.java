package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.intensityadjust.AffineIntensityCorrectionStrategy;

/**
 * Parameters for rendering intensity adjusted montage scapes for layers within a stack.
 *
 * @author Eric Trautman
 */
@Parameters
public class IntensityAdjustParameters
        extends CommandLineParameters {

    public enum CorrectionMethod {
        DEFAULT, GAUSS, GAUSS_WEIGHTED, GLOBAL_PER_SLICE
    }

    public enum StrategyName {
        AFFINE, FIRST_LAYER_QUADRATIC, ALL_LAYERS_QUADRATIC
    }

    @ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

    @Parameter(
            names = "--stack",
            description = "Stack name",
            required = true)
    public String stack;

    @Parameter(
            names = "--matchCollection",
            description = "Match collection to identify connected tiles (omit to use tile bounds and zDistance)")
    public String matchCollection;

    @Parameter(
            names = "--intensityCorrectedFilterStack",
            description = "Name of stack to store tile specs with intensity corrected filter data.  " +
                          "Omit to render intensity corrected scape-images to disk.")
    public String intensityCorrectedFilterStack;

    @Parameter(
            names = "--rootDirectory",
            description = "Root directory for rendered layers (e.g. /nrs/flyem/render/scapes)")
    public String rootDirectory;

    @Parameter(
            names = "--dataSetPath",
            description = "Explicit path to append to the root directory for rendered layers " +
                          "(e.g. /Z0720_7m_BR/Sec39/v1_align/intensity_adjusted_scapes_20210501_112233).  " +
                          "Omit to have auto generated data set path.")
    public String dataSetPath;

    @ParametersDelegate
    public ZRangeParameters layerRange = new ZRangeParameters();

    @Parameter(
            names = "--z",
            description = "Explicit z values for sections to be processed",
            variableArity = true) // e.g. --z 20.0 21.0 22.0
    public List<Double> zValues;

    @Parameter(
            names = "--format",
            description = "Format for rendered boxes"
    )
    public String format = Utils.PNG_FORMAT;

    @Parameter(
            names = "--correctionMethod",
            description = "Correction method to use")
    public CorrectionMethod correctionMethod;

    @Parameter(
            names = "--strategy",
            description = "Strategy to use")
    public StrategyName strategyName = StrategyName.AFFINE;

    @Parameter(
            names = "--numThreads",
            description = "Number of threads to use")
    public Integer numThreads = 1;

    @Parameter(
            names = "--lambda1",
            description = "First lambda for strategy model")
    public Double lambda1 = AffineIntensityCorrectionStrategy.DEFAULT_LAMBDA;

    @Parameter(
            names = "--lambda2",
            description = "Second lambda for strategy model")
    public Double lambda2 = AffineIntensityCorrectionStrategy.DEFAULT_LAMBDA;

    @Parameter(
            names = { "--maxPixelCacheGb" },
            description = "Maximum number of gigabytes of pixels to cache"
    )
    public Integer maxPixelCacheGb = 1;

    @Parameter(
            names = "--completeCorrectedStack",
            description = "Complete the intensity corrected stack after processing",
            arity = 0)
    public boolean completeCorrectedStack = false;

    @Parameter(
            names = "--renderScale",
            description = "Scale for rendered tiles used during intensity comparison")
    public double renderScale = 0.1;

    @Parameter(
            names = "--zDistance",
            description = "If specified, apply correction across this many z-layers from the current z-layer " +
                          "(omit to only correct in 2D)")
    public Integer zDistance;

    @Parameter(
            names = { "--numCoefficients" },
            description = "Number of correction coefficients to derive in each dimension " +
                          "(e.g. value of 8 will divide each tile into 8x8 = 64 sub-regions)"
    )
    public int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;

    public File getSectionRootDirectory(final Date forRunTime) {

        final Path sectionRootPath;
        if (dataSetPath == null) {
            final String scapeDir = "intensity_adjusted_scapes_" +
                                    new SimpleDateFormat("yyyyMMdd_HHmmss").format(forRunTime);
            sectionRootPath = Paths.get(rootDirectory,
                                        renderWeb.owner,
                                        renderWeb.project,
                                        stack,
                                        scapeDir).toAbsolutePath();
        } else {
            sectionRootPath = Paths.get(rootDirectory, dataSetPath);
        }

        return sectionRootPath.toFile();
    }

    public String getSlicePathFormatSpec(final StackMetaData stackMetaData,
                                          final File sectionRootDirectory) {
        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();
        int maxZCharacters = 5;
        for (long z = 100000; z < stackBounds.getMaxZ().longValue(); z = z * 10) {
            maxZCharacters++;
        }
        return sectionRootDirectory.getAbsolutePath() + "/z.%0" + maxZCharacters + "d." + format;
    }

    public long getMaxNumberOfCachedPixels() {
        return maxPixelCacheGb * 1_000_000_000L;
    }

    public void validateAndSetDefaults() throws IllegalArgumentException {
        if ((correctionMethod != null) && (! CorrectionMethod.GLOBAL_PER_SLICE.equals(correctionMethod))) {
            throw new IllegalArgumentException(
                    "current implementation only supports --correctionMethod of " +
                    CorrectionMethod.GLOBAL_PER_SLICE + ", so specify that or omit the parameter");
        }

        if ((intensityCorrectedFilterStack == null) && (rootDirectory == null)) {
                throw new IllegalArgumentException(
                        "must specify either --intensityCorrectedFilterStack or --rootDirectory");
        }
    }

    public boolean deriveFilterData() {
        return intensityCorrectedFilterStack != null;
    }

    public boolean correctIn3D() {
        return (zDistance != null) && (zDistance > 0);
    }
}