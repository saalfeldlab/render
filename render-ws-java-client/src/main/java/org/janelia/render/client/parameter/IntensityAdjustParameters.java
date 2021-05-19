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

    @ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

    @Parameter(
            names = "--stack",
            description = "Stack name",
            required = true)
    public String stack;

    @Parameter(
            names = "--rootDirectory",
            description = "Root directory for rendered layers (e.g. /nrs/flyem/render/scapes)",
            required = true)
    public String rootDirectory;

    @Parameter(
            names = "--dataSetPath",
            description = "Explicit path to append to the root directory for rendered layers " +
                          "(e.g. /Z0720_7m_BR/Sec39/v1_align/intensity_adjusted_scapes_20210501_112233).  " +
                          "Omit to have auto generated data set path.",
            required = true)
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
    public CorrectionMethod correctionMethod = CorrectionMethod.DEFAULT;

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

}