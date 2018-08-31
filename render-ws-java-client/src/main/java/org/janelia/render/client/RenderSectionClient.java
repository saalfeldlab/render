package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering a composite image of all tiles in a section for one or more sections.
 * Images are placed in [rootDirectory]/[project]/[stack]/sections_at_[scale]/000/1/123.png
 *
 * @author Eric Trautman
 */
public class RenderSectionClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--rootDirectory",
                description = "Root directory for rendered layers (e.g. /tier2/flyTEM/nobackup/rendered_boxes)",
                required = true)
        public String rootDirectory;

        @Parameter(
                names = "--scale",
                description = "Scale for each rendered layer"
        )
        public Double scale = 0.02;

        @Parameter(
                names = "--format",
                description = "Format for rendered boxes"
        )
        public String format = Utils.PNG_FORMAT;

        @Parameter(
                names = "--doFilter",
                description = "Use ad hoc filter to support alignment",
                arity = 1)
        public boolean doFilter = true;

        @Parameter(
                names = "--filterListName",
                description = "Apply this filter list to all rendering (overrides doFilter option)"
        )
        public String filterListName;

        @Parameter(
                names = "--channels",
                description = "Specify channel(s) and weights to render (e.g. 'DAPI' or 'DAPI__0.7__TdTomato__0.3')"
        )
        public String channels;

        @Parameter(
                names = "--fillWithNoise",
                description = "Fill image with noise before rendering to improve point match derivation",
                arity = 1)
        public boolean fillWithNoise = true;

        @Parameter(
                description = "Z values for sections to render",
                required = true)
        public List<Double> zValues;

        @Parameter(
                names = "--bounds",
                description = "Bounds used for all layers: xmin, xmax, ymin,ymax"
        )
        public List<Integer> bounds;

        @Parameter(
                names = "--customOutputFolder",
                description = "Custom named folder for output. Overrides the default format 'sections_at_#' folder"
        )
        public String customOutputFolder;

        @Parameter(
                names = "--customSubFolder",
                description = "Name for subfolder to customOutputFolder, if used"
        )
        public String customSubFolder;

        @Parameter(
                names = "--padFileNamesWithZeros",
                description = "Pad outputfilenames with leading zeroes, i.e. 12.tiff -> 00012.tiff"
        )
        public boolean padFileNameWithZeroes;

        @Parameter(
                names = "--maxIntensity",
                description = "Max intensity to render image"
        )
        public Integer maxIntensity;

        @Parameter(
                names = "--minIntensity",
                description = "Min intensity to render image"
        )
        public Integer minIntensity;
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

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

    private RenderSectionClient(final Parameters clientParameters) {

        this.clientParameters = clientParameters;

        final Path sectionPath;
        if (clientParameters.customOutputFolder != null) {
            if (clientParameters.customSubFolder != null) {
                sectionPath = Paths.get(clientParameters.rootDirectory,
                                        clientParameters.customOutputFolder,
                                        clientParameters.customSubFolder);
            } else {
                sectionPath = Paths.get(clientParameters.rootDirectory,
                                        clientParameters.customOutputFolder);
            }
        } else {
            final String sectionsAtScaleName = "sections_at_" + clientParameters.scale;
            sectionPath = Paths.get(clientParameters.rootDirectory,
                                    clientParameters.renderWeb.project,
                                    clientParameters.stack,
                                    sectionsAtScaleName);
        }

        this.sectionDirectory = sectionPath.toAbsolutePath().toFile();

        FileUtil.ensureWritableDirectory(this.sectionDirectory);

        // set cache size to 50MB so that masks get cached but most of RAM is left for target image
        final int maxCachedPixels = 50 * 1000000;
        this.imageProcessorCache = new ImageProcessorCache(maxCachedPixels, false, false);

        this.renderDataClient = clientParameters.renderWeb.getDataClient();
    }

    private void generateImageForZ(final Double z)
            throws Exception {

        LOG.info("generateImageForZ: {}, entry, sectionDirectory={}, dataClient={}",
                 z, sectionDirectory, renderDataClient);

        final Bounds layerBounds = renderDataClient.getLayerBounds(clientParameters.stack, z);

        String parametersUrl; 
        if(clientParameters.bounds != null && clientParameters.bounds.size() == 4) //Read bounds from supplied parameters
        {
            LOG.debug("Using user bounds");
            parametersUrl = 
                renderDataClient.getRenderParametersUrlString(clientParameters.stack,
                                                              clientParameters.bounds.get(0), //Min X 
                                                              clientParameters.bounds.get(2), //Min Y
                                                              z,
                                                              clientParameters.bounds.get(1) - clientParameters.bounds.get(0), //Width
                                                              clientParameters.bounds.get(3) - clientParameters.bounds.get(2), //Height
                                                              clientParameters.scale,
                                                              clientParameters.filterListName);

        }
        else //Get bounds from render
        {
            LOG.debug("Using render bounds");
            parametersUrl =
                renderDataClient.getRenderParametersUrlString(clientParameters.stack,
                                                              layerBounds.getMinX(),
                                                              layerBounds.getMinY(),
                                                              z,
                                                              (int) (layerBounds.getDeltaX() + 0.5),
                                                              (int) (layerBounds.getDeltaY() + 0.5),
                                                              clientParameters.scale,
                                                              clientParameters.filterListName);
        }

        if (clientParameters.minIntensity != null) {

            if (clientParameters.maxIntensity != null) {
                parametersUrl += "?minIntensity=" + clientParameters.minIntensity +
                                 "&maxIntensity=" + clientParameters.maxIntensity;
            } else {
                parametersUrl += "?minIntensity=" + clientParameters.minIntensity;
            }

        } else if (clientParameters.maxIntensity != null) {
            parametersUrl += "?maxIntensity=" + clientParameters.maxIntensity;
        }

        LOG.debug("generateImageForZ: {}, loading {}", z, parametersUrl);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);
        renderParameters.setFillWithNoise(clientParameters.fillWithNoise);
        renderParameters.setDoFilter(clientParameters.doFilter);
        renderParameters.setChannels(clientParameters.channels);

        final File sectionFile = getSectionFile(z);

        final BufferedImage sectionImage = renderParameters.openTargetImage();

        ArgbRenderer.render(renderParameters, sectionImage, imageProcessorCache);

        Utils.saveImage(sectionImage, sectionFile.getAbsolutePath(), clientParameters.format, true, 0.85f);

        LOG.info("generateImageForZ: {}, exit", z);
    }

    private File getSectionFile(final Double z) {

        final String fileName = clientParameters.padFileNameWithZeroes ?
                                String.format("%05d", z.intValue()) : z.toString();

        final File parentDirectory;
        if (clientParameters.customOutputFolder == null) {

            final int thousands = z.intValue() / 1000;
            final File thousandsDir = new File(sectionDirectory, String.format("%03d", thousands));

            final int hundreds = (z.intValue() % 1000) / 100;
            parentDirectory = new File(thousandsDir, String.valueOf(hundreds));

        } else {

            parentDirectory = sectionDirectory;

        }

        FileUtil.ensureWritableDirectory(parentDirectory);

        return new File(parentDirectory, fileName + "." + clientParameters.format.toLowerCase());
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderSectionClient.class);
}
