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

    private String service_host;
    private String baseURL;
    private String owner;
    private String project;
    private String stack;
    private Integer verbose;
    private String versionNotes;

    public AlignmentRenderCollection() {
        // To be used by JSON reader
    }
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

    public String getServiceHost() {
        return service_host;
    }

    public void setServiceHost(final String service_host) {
        this.service_host = service_host;
    }

    public String getBaseURL() {
        return baseURL;
    }

    public void setBaseURL(final String baseURL) {
        this.baseURL = baseURL;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(final String owner) {
        this.owner = owner;
    }

    public String getProject() {
        return project;
    }

    public void setProject(final String project) {
        this.project = project;
    }

    public String getStack() {
        return stack;
    }

    public void setStack(final String stack) {
        this.stack = stack;
    }

    public Integer getVerbose() {
        return verbose;
    }

    public void setVerbose(final Integer verbose) {
        this.verbose = verbose;
    }
}
