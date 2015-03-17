package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering a composite image of all tiles in a section for one or more sections.
 *
 * @author Eric Trautman
 */
public class RenderSectionClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Stack name", required = true)
        private String stack;

        @Parameter(names = "--rootDirectory", description = "Root directory for rendered layers (e.g. /tier2/flyTEM/nobackup/rendered_boxes)", required = true)
        private String rootDirectory;

        @Parameter(names = "--scale", description = "Scale for each rendered layer (default is 0.02)", required = false)
        private Double scale = 0.02;

        @Parameter(names = "--format", description = "Format for rendered boxes (default is PNG)", required = false)
        private String format = Utils.PNG_FORMAT;

        @Parameter(description = "Z values for sections to render", required = true)
        private List<Double> zValues;
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(String[] args) {
        try {

            final Parameters parameters = new Parameters();
            parameters.parse(args);

            LOG.info("main: entry, parameters={}", parameters);

            final RenderSectionClient client = new RenderSectionClient(parameters);

            for (Double z : parameters.zValues) {
                client.generateImageForZ(z);
            }

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
        }
    }

    private final Parameters params;

    private final File sectionDirectory;
    private final ImageProcessorCache imageProcessorCache;
    private final RenderDataClient renderDataClient;

    public RenderSectionClient(final Parameters params) {

        this.params = params;

        final Path projectPath = Paths.get(params.rootDirectory,
                                           params.project).toAbsolutePath();

        final File projectDirectory = projectPath.toFile();
        if (! projectDirectory.exists()) {
            throw new IllegalArgumentException("missing project directory " + projectDirectory);
        }

        final String sectionsAtScaleName = "sections_at_" + params.scale;
        final Path sectionPath = Paths.get(projectPath.toString(),
                                           params.stack,
                                           sectionsAtScaleName).toAbsolutePath();

        this.sectionDirectory = sectionPath.toFile();
        ensureWritableDirectory(this.sectionDirectory);

        // set cache size to 50MB so that masks get cached but most of RAM is left for target image
        final int maxCachedPixels = 50 * 1000000;
        this.imageProcessorCache = new ImageProcessorCache(maxCachedPixels, false, false);

        this.renderDataClient = params.getClient();
    }

    public void generateImageForZ(final Double z)
            throws Exception {

        LOG.info("generateImageForZ: {}, entry, sectionDirectory={}, dataClient={}",
                 z, sectionDirectory, renderDataClient);

        final Bounds layerBounds = renderDataClient.getLayerBounds(params.stack, z);
        final String parametersUrl =
                renderDataClient.getRenderParametersUrlString(params.stack,
                                                              layerBounds.getMinX(),
                                                              layerBounds.getMinY(),
                                                              z,
                                                              (int) (layerBounds.getDeltaX() + 0.5),
                                                              (int) (layerBounds.getDeltaY() + 0.5),
                                                              params.scale);

        LOG.debug("generateImageForZ: {}, loading {}", z, parametersUrl);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);

        final File sectionFile = getSectionFile(z);

        final BufferedImage sectionImage = renderParameters.openTargetImage();

        Render.render(renderParameters, sectionImage, imageProcessorCache);

        Utils.saveImage(sectionImage, sectionFile.getAbsolutePath(), params.format, false, 0.85f);

        LOG.info("generateBoxesForZ: {}, exit", z);
    }

    private File getSectionFile(final Double z) {
        final int thousands = z.intValue() / 1000;
        final File thousandsDir = new File(sectionDirectory, getNumericDirectoryName(thousands));

        final int hundreds = (z.intValue() % 1000) / 100;
        final File hundredsDir = new File(thousandsDir, getNumericDirectoryName(hundreds));

        ensureWritableDirectory(hundredsDir);

        return new File(hundredsDir, z + "." + params.format.toLowerCase());
    }

    private void ensureWritableDirectory(final File directory) {
        // try twice to work around concurrent access issues
        if (! directory.exists()) {
            if (! directory.mkdirs()) {
                if (! directory.exists()) {
                    // last try
                    if (! directory.mkdirs()) {
                        if (! directory.exists()) {
                            throw new IllegalArgumentException("failed to create " + directory);
                        }
                    }
                }
            }
        }
        if (! directory.canWrite()) {
            throw new IllegalArgumentException("not allowed to write to " + directory);
        }
    }

    private String getNumericDirectoryName(final int value) {
        String pad = "00";
        if (value > 99) {
            pad = "";
        } else if (value > 9) {
            pad = "0";
        }
        return pad + value;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderSectionClient.class);
}
