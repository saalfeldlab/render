package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import ij.process.ByteProcessor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering a composite image of all tiles in a section for one or more sections.
 * Images are placed in [rootDirectory]/[project]/[stack]/sections_at_[scale]/000/001/123.png
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

        @Parameter(names = "--scale", description = "Scale for each rendered layer", required = false)
        private Double scale = 0.02;

        @Parameter(names = "--format", description = "Format for rendered boxes", required = false)
        private String format = Utils.PNG_FORMAT;

        @Parameter(names = "--doFilter", description = "Use ad hoc filter to support alignment", required = false, arity = 1)
        private boolean doFilter = true;

        @Parameter(names = "--channels", description = "Specify channel(s) and weights to render (e.g. 'DAPI' or 'DAPI__0.7__TdTomato__0.3')", required = false)
        private String channels;

        @Parameter(names = "--fillWithNoise", description = "Fill image with noise before rendering to improve point match derivation", required = false, arity = 1)
        private boolean fillWithNoise = true;

        @Parameter(description = "Z values for sections to render", required = true)
        private List<Double> zValues;
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, RenderSectionClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderSectionClient client = new RenderSectionClient(parameters);

                for (final Double z : parameters.zValues) {
                    client.generateImageForZ(z);
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters clientParameters;

    private final File sectionDirectory;
    private final ImageProcessorCache imageProcessorCache;
    private final RenderDataClient renderDataClient;

    public RenderSectionClient(final Parameters clientParameters) {

        this.clientParameters = clientParameters;

        final Path projectPath = Paths.get(clientParameters.rootDirectory,
                                           clientParameters.project).toAbsolutePath();

        final String sectionsAtScaleName = "sections_at_" + clientParameters.scale;
        final Path sectionPath = Paths.get(projectPath.toString(),
                                           clientParameters.stack,
                                           sectionsAtScaleName).toAbsolutePath();

        this.sectionDirectory = sectionPath.toFile();
        FileUtil.ensureWritableDirectory(this.sectionDirectory);

        // set cache size to 50MB so that masks get cached but most of RAM is left for target image
        final int maxCachedPixels = 50 * 1000000;
        this.imageProcessorCache = new ImageProcessorCache(maxCachedPixels, false, false);

        this.renderDataClient = new RenderDataClient(clientParameters.baseDataUrl,
                                                     clientParameters.owner,
                                                     clientParameters.project);
    }

    public void generateImageForZ(final Double z)
            throws Exception {

        LOG.info("generateImageForZ: {}, entry, sectionDirectory={}, dataClient={}",
                 z, sectionDirectory, renderDataClient);

        final Bounds layerBounds = renderDataClient.getLayerBounds(clientParameters.stack, z);
        final String parametersUrl =
                renderDataClient.getRenderParametersUrlString(clientParameters.stack,
                                                              layerBounds.getMinX(),
                                                              layerBounds.getMinY(),
                                                              z,
                                                              (int) (layerBounds.getDeltaX() + 0.5),
                                                              (int) (layerBounds.getDeltaY() + 0.5),
                                                              clientParameters.scale);

        LOG.debug("generateImageForZ: {}, loading {}", z, parametersUrl);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);
        renderParameters.setDoFilter(clientParameters.doFilter);
        renderParameters.setChannels(clientParameters.channels);

        final File sectionFile = getSectionFile(z);

        final BufferedImage sectionImage = renderParameters.openTargetImage();

        if (clientParameters.fillWithNoise) {
            final ByteProcessor ip = new ByteProcessor(sectionImage.getWidth(), sectionImage.getHeight());
            mpicbg.ij.util.Util.fillWithNoise(ip);
            sectionImage.getGraphics().drawImage(ip.createImage(), 0, 0, null);
        }

        ArgbRenderer.render(renderParameters, sectionImage, imageProcessorCache);

        Utils.saveImage(sectionImage, sectionFile.getAbsolutePath(), clientParameters.format, true, 0.85f);

        LOG.info("generateImageForZ: {}, exit", z);
    }

    private File getSectionFile(final Double z) {
        final int thousands = z.intValue() / 1000;
        final File thousandsDir = new File(sectionDirectory, getNumericDirectoryName(thousands));

        final int hundreds = (z.intValue() % 1000) / 100;
        final File hundredsDir = new File(thousandsDir, String.valueOf(hundreds));

        FileUtil.ensureWritableDirectory(hundredsDir);

        return new File(hundredsDir, z + "." + clientParameters.format.toLowerCase());
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
