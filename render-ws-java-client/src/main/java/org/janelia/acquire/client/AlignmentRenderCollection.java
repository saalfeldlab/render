package org.janelia.acquire.client;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Set of render collection information for alignment tools.
 *
 * See <a href="https://github.com/khaledkhairy/EM_aligner/blob/master/matlab_compiled/sample_montage_input.json">
 *     https://github.com/khaledkhairy/EM_aligner/blob/master/matlab_compiled/sample_montage_input.json
 * </a>
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class AlignmentRenderCollection {

    private final String service_host;
    private final String baseURL;
    private final String owner;
    private final String project;
    private final String stack;
    private final Integer verbose;

    public AlignmentRenderCollection(final String baseURL,
                                     final String owner,
                                     final String project,
                                     final String stack)
            throws MalformedURLException {
        this.baseURL = baseURL;
        this.service_host = getHostAndPort(baseURL);
        this.owner = owner;
        this.project = project;
        this.stack = stack;
        this.verbose = 1;
    }

    public static String getHostAndPort(final String urlString)
            throws MalformedURLException {
        final URL url = new URL(urlString);
        String hostAndPort = url.getHost();
        final int port = url.getPort();
        if (port != -1) {
            hostAndPort = hostAndPort + ':' + port;
        }
        return hostAndPort;
    }
}
