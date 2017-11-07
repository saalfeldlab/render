package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.janelia.alignment.RenderParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.response.BufferedImageResponseHandler;
import org.janelia.render.client.response.FileResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple client for rendering based upon a JSON parameters spec.
 *
 * @author Eric Trautman
 */
public class RenderClient {

    public static final String JPEG_FORMAT = "jpeg";
    public static final String PNG_FORMAT = "png";

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(names = "--in",
                description = "Path to render parameters json file",
                required = true)
        public String in;

        @Parameter(names = "--out",
                description = "Path for the output image file (if omitted, image will be displayed in window)",
                required = false)
        public String out;

        @Parameter(names = "--format",
                description = "Format for output image (jpeg or png, default is jpeg)",
                required = false)
        public String format;

        public boolean renderInWindow() {
            return (out == null);
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderClient client = new RenderClient(parameters.renderWeb.baseDataUrl,
                                                             parameters.renderWeb.project);

                if (parameters.renderInWindow()) {
                    client.renderInWindow(parameters.in,
                                          parameters.out);
                } else {
                    client.renderToFile(parameters.in,
                                        parameters.out,
                                        parameters.format);
                }
            }
        };
        clientRunner.run();
    }

    private final Map<String, URI> formatToRenderUriMap;
    private final CloseableHttpClient httpClient;

    /**
     * @param  baseUriString  base URI for web service (e.g. http://renderer.int.janelia.org/render-ws/v1).
     * @param  owner          owner (e.g. 'flyTEM') for all requests.
     *
     * @throws IllegalArgumentException
     *   if the render URI instances cannot be constructed.
     */
    public RenderClient(final String baseUriString,
                        final String owner) throws IllegalArgumentException {

        final String trimmedBaseUriString;
        if (baseUriString.endsWith("/")) {
            trimmedBaseUriString = baseUriString.substring(0, baseUriString.length() - 1);
        } else {
            trimmedBaseUriString = baseUriString;
        }

        final String projectUriString = trimmedBaseUriString + "/owner/" + owner;
        final URI jpegUri = createUri(projectUriString + "/jpeg-image");
        final URI pngUri = createUri(projectUriString + "/png-image");
        final Map<String, URI> map = new LinkedHashMap<>();
        map.put(JPEG_FORMAT.toLowerCase(), jpegUri);
        map.put(PNG_FORMAT.toLowerCase(), pngUri);
        this.formatToRenderUriMap = map;

        this.httpClient = HttpClients.createDefault();
    }

    /**
     * @param  format  image format.
     *
     * @return render URI for the specified format.
     */
    public URI getRenderUriForFormat(final String format) {

        final String lowerCaseFormat;
        if (format == null) {
            lowerCaseFormat = JPEG_FORMAT;
        } else {
            lowerCaseFormat = format.toLowerCase();
        }

        URI uri = formatToRenderUriMap.get(lowerCaseFormat);
        if (uri == null) {
            LOG.warn("getRenderUriForFormat: unknown format '" + format + "' requested, using '" +
                     JPEG_FORMAT + "' instead, known formats are: " + formatToRenderUriMap.keySet());
            uri = formatToRenderUriMap.get(JPEG_FORMAT);
        }

        return uri;
    }

    @Override
    public String toString() {
        return "RenderClient{formatToRenderUriMap=" + formatToRenderUriMap + '}';
    }

    /**
     * Invokes the Render Web Service using parameters loaded from the specified file and
     * writes the resulting image to the specified output file.
     *
     * @param  renderParametersPath  path of render parameters (json) file.
     * @param  outputFilePath        path of result image file.
     * @param  outputFormat          format of result image (e.g. 'jpeg' or 'png').
     *
     * @throws IOException
     *   if the render request fails for any reason.
     */
    public void renderToFile(final String renderParametersPath,
                             final String outputFilePath,
                             final String outputFormat)
            throws IOException {

        final File parametersFile = new File(renderParametersPath);
        final RenderParameters renderParameters = RenderParameters.parseJson(parametersFile);

        final File outputFile = new File(outputFilePath).getCanonicalFile();

        renderToFile(renderParameters, outputFile, outputFormat);
    }

    /**
     * Invokes the Render Web Service using specified parameters and
     * writes the resulting image to the specified output file.
     *
     * @param  renderParameters  render parameters.
     * @param  outputFile        result image file.
     * @param  outputFormat      format of result image (e.g. 'jpeg' or 'png').
     *
     * @throws IOException
     *   if the render request fails for any reason.
     */
    public void renderToFile(final RenderParameters renderParameters,
                             final File outputFile,
                             final String outputFormat)
            throws IOException {

        LOG.info("renderToFile: entry, renderParameters={}, outputFile={}, outputFormat={}",
                 renderParameters, outputFile, outputFormat);

        if (outputFile.exists()) {
            if (! outputFile.canWrite()) {
                throw new IOException("output file " + outputFile.getAbsolutePath() + " cannot be overwritten");
            }
        } else {
            File parentFile = outputFile.getParentFile();
            while (parentFile != null) {
                if (parentFile.exists()) {
                    if (! parentFile.canWrite()) {
                        throw new IOException("output file cannot be written to parent directory " +
                                              parentFile.getAbsolutePath());
                    }
                    break;
                }
                parentFile = parentFile.getParentFile();
            }
        }

        final String json = renderParameters.toJson();
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getRenderUriForFormat(outputFormat);
        final FileResponseHandler responseHandler = new FileResponseHandler("PUT " + uri,
                                                                            outputFile);
        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        httpClient.execute(httpPut, responseHandler);

        LOG.info("renderToFile: exit, wrote image to {}", outputFile.getAbsolutePath());
    }

    /**
     * Invokes the Render Web Service using parameters loaded from the specified file and
     * displays the resulting image in a window.
     *
     * @param  renderParametersPath  path of render parameters (json) file.
     * @param  outputFormat          format of result image (e.g. 'jpeg' or 'png').
     *
     * @throws IOException
     *   if the render request fails for any reason.
     */
    public void renderInWindow(final String renderParametersPath,
                               final String outputFormat)
            throws IOException {
        final File parametersFile = new File(renderParametersPath);
        final RenderParameters renderParameters = RenderParameters.parseJson(parametersFile);
        renderInWindow(renderParameters, outputFormat);
    }

    /**
     * Invokes the Render Web Service using specified parameters and
     * displays the resulting image in a window.
     *
     * @param  renderParameters  render parameters.
     * @param  outputFormat      format of result image (e.g. 'jpeg' or 'png').
     *
     * @throws IOException
     *   if the render request fails for any reason.
     */
    public void renderInWindow(final RenderParameters renderParameters,
                               final String outputFormat)
            throws IOException {

        LOG.info("renderInWindow: entry, renderParameters={}, outputFormat={}", renderParameters, outputFormat);

        final String json = renderParameters.toJson();
        final StringEntity stringEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
        final URI uri = getRenderUriForFormat(outputFormat);
        final BufferedImageResponseHandler responseHandler = new BufferedImageResponseHandler("PUT " + uri);
        final HttpPut httpPut = new HttpPut(uri);
        httpPut.setEntity(stringEntity);

        final BufferedImage image = httpClient.execute(httpPut, responseHandler);

        final ScrollableImageWindow window = new ScrollableImageWindow(renderParameters.toString(), image);
        window.setVisible(true);

        LOG.info("renderInWindow: exit, created {}", image);
    }

    private URI createUri(final String uriString) throws IllegalArgumentException {
        final URI uri;
        try {
            uri = new URI(uriString);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("failed to parse URI string: " + uriString, e);
        }
        return uri;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderClient.class);
}
