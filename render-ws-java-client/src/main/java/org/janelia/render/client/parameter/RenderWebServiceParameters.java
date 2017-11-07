package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

import org.janelia.render.client.RenderDataClient;

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

    @JsonIgnore
    public RenderDataClient getDataClient() {
        return new RenderDataClient(baseDataUrl, owner, project);
    }
}