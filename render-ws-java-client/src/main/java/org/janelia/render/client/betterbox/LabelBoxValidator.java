package org.janelia.render.client.betterbox;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.betterbox.BoxData;
import org.janelia.alignment.util.LabelImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.parameter.MaterializedBoxParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for validating rendered label boxes.
 *
 * @author Eric Trautman
 */
public class LabelBoxValidator
        implements Serializable {

    private final RenderWebServiceUrls webServiceUrls;

    private final String stack;
    private final int boxWidth;
    private final int boxHeight;
    private final String baseBoxPath;
    private final String boxPathSuffix;

    private final LabelBoxValidationResult result;

    /**
     * Constructs a validator with the specified parameters.
     *
     * @param  renderWebParameters  web service parameters for retrieving render stack data.
     * @param  boxParameters        parameters specifying box properties.
     */
    public LabelBoxValidator(final RenderWebServiceParameters renderWebParameters,
                             final MaterializedBoxParameters boxParameters) {

        this.webServiceUrls = new RenderWebServiceUrls(renderWebParameters.baseDataUrl,
                                                       renderWebParameters.owner,
                                                       renderWebParameters.project);
        this.stack = boxParameters.stack;
        this.boxWidth = boxParameters.width;
        this.boxHeight = boxParameters.height;

        final String boxName = this.boxWidth + "x" + this.boxHeight + "-label";

        final Path boxPath = Paths.get(boxParameters.rootDirectory,
                                       renderWebParameters.project,
                                       boxParameters.stack,
                                       boxName).toAbsolutePath();

        final File boxDirectory = boxPath.toFile().getAbsoluteFile();
        this.baseBoxPath = boxDirectory.getPath();
        this.boxPathSuffix = "." + boxParameters.format.toLowerCase();

        this.result = new LabelBoxValidationResult();
    }

    /**
     * Validates the specified label boxes.
     *
     * @param  boxList  list of boxes to validate.
     */
    public LabelBoxValidationResult validateLabelBoxes(final List<BoxData> boxList) {

        LOG.info("validateLabelBoxes: entry, boxList.size={}", boxList.size());

        for (final BoxData boxData : boxList) {
            if (boxData.getLevel() == 0) {
                validateBoxData(boxData);
            }
        }

        LOG.info("validateLabelBoxes: exit, checked {} boxes", boxList.size());

        return result;
    }

    private void validateBoxData(final BoxData boxData) {
        validateBoxFile(boxData.getAbsoluteLevelFile(baseBoxPath, boxPathSuffix),
                        boxData.getZ(),
                        boxData.getServicePath(boxWidth, boxHeight));
    }

    private void validateBoxFile(final File boxFile,
                                 final Double boxZ,
                                 final String servicePath) {

        if (boxFile.exists()) {

            try {

                final Set<Integer> nonEmptyBoxColors = getNonEmptyLabelColors(boxFile.getAbsolutePath());

                result.addNonEmptyLabelColors(boxZ, nonEmptyBoxColors);

                final String boxParametersUrl = webServiceUrls.getStackUrlString(stack) +
                                                servicePath +
                                                "/render-parameters";
                final RenderParameters renderParameters = RenderParameters.loadFromUrl(boxParametersUrl);

                result.addTileIds(boxZ, renderParameters.getTileSpecs());

                // Lesson Learned:
                //   Comparing color count to tile count here is not useful because box bounds occasionally
                //   clip tiles such that only the masked area is within the box.
                //   Mask areas don't get labelled, so a box's color count may not necessarily match its tile count.

            } catch (final IOException e) {

                result.addErrorMessage(boxZ, e.getMessage());

            }

        } else {

            result.addErrorMessage(boxZ,
                                   boxFile + " does not exist");

        }

    }

    public static Set<Integer> getNonEmptyLabelColors(final String labelPath)
            throws IOException {

        final ImagePlus labelImagePlus = Utils.openImagePlus(labelPath);

        if (labelImagePlus == null) {
            throw new IOException(labelPath + " could not be loaded into an ImagePlus instance");
        }

        final ImageProcessor ip = labelImagePlus.getProcessor();

        final Set<Integer> colorSet = new HashSet<>();
        for (int y = 0; y < ip.getHeight(); y++) {
            for (int x = 0; x < ip.getWidth(); x++) {
                final int rgb = ip.get(x, y);
                if (rgb != LabelImageProcessorCache.MAX_LABEL_INTENSITY) {
                    colorSet.add(rgb);
                }
            }
        }

        return colorSet;
    }

    public static void main(final String[] args) {

        // can also use imagemagick to do this:
        //   identify -format %k filename

        String[] effectiveArgs = args;
        if (args.length == 0) {
            effectiveArgs = new String[] {
                "/Users/trautmane/Desktop/dmg_bleach_problem/label/row_6_col_19.png",
                "/Users/trautmane/Desktop/dmg_bleach_problem/label/row_6_col_20.png",
                "/Users/trautmane/Desktop/dmg_bleach_problem/label/row_6_col_21.png",
                "/Users/trautmane/Desktop/dmg_bleach_problem/label/row_7_col_19.png",
                "/Users/trautmane/Desktop/dmg_bleach_problem/label/row_7_col_20.png",
                "/Users/trautmane/Desktop/dmg_bleach_problem/label/row_7_col_21.png"
            };
        }

        for (final String labelPath : effectiveArgs) {
            try {
                final Set<Integer> nonEmptyLabelColors = getNonEmptyLabelColors(labelPath);
                LOG.info("found {} distinct non-empty label colors in {}", nonEmptyLabelColors.size(), labelPath);
            } catch (final IOException e) {
                LOG.error("failed to check " + labelPath, e);
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(LabelBoxValidator.class);

}
