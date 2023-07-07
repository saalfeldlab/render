package org.janelia.render.client.parameter;

import java.io.Serializable;

import org.janelia.render.client.RenderDataClient;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Parameters for render web service clients.
 *
 * @author Eric Trautman
 */
@Parameters
public class RenderWebServiceParameters implements Serializable {

    @Parameter(
            names = "--baseDataUrl",
            description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
            required = true)
    public String baseDataUrl;

    @Parameter(
            names = "--owner",
            description = "Stack owner",
            required = true)
    public String owner;

    @Parameter(
            names = "--project",
            description = "Stack project",
            required = true)
    public String project;

    /** Local data client instance that is lazy loaded since client cannot be serialized. */
    private transient RenderDataClient dataClient;

    public RenderWebServiceParameters() {
        this(null, null, null);
    }

    public RenderWebServiceParameters(final String baseDataUrl,
                                      final String owner,
                                      final String project) {
        this.baseDataUrl = baseDataUrl;
        this.owner = owner;
        this.project = project;
    }

    @JsonIgnore
    public RenderDataClient getDataClient() {
        if (dataClient == null) {
            buildDataClient();
        }
        return dataClient;
    }

    private synchronized void buildDataClient() {
        if (dataClient == null) {
            dataClient = new RenderDataClient(baseDataUrl, owner, project);
        }
    }
}