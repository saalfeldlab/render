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

    @ParametersDelegate
    public AlgorithmicIntensityAdjustParameters algorithmic = new AlgorithmicIntensityAdjustParameters();

    @ParametersDelegate
    public ZRangeParameters layerRange = new ZRangeParameters();

    @Parameter(
            names = "--completeCorrectedStack",
            description = "Complete the intensity corrected stack after processing",
            arity = 0)
    public boolean completeCorrectedStack = false;

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

    public File getSectionRootDirectory(final Date forRunTime) {

        final Path sectionRootPath;
        if (dataSetPath == null) {
            final String scapeDir = "intensity_adjusted_scapes_" +
                                    new SimpleDateFormat("yyyyMMdd_HHmmss").format(forRunTime);
            sectionRootPath = Paths.get(rootDirectory,
                                        renderWeb.owner,
                                        renderWeb.project,
                                        algorithmic.stack,
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
        return algorithmic.maxPixelCacheGb * 1_000_000_000L;
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
        return (algorithmic.zDistance != null) && (algorithmic.zDistance > 0);
    }
}