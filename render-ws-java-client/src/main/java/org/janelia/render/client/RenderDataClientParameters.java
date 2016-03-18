package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

/**
 * Base parameters for all render web service clients.
 *
 * @author Eric Trautman
 */
@Parameters
public class RenderDataClientParameters
        extends CommandLineParameters {

    @Parameter(
            names = "--baseDataUrl",
            description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
            required = true)
    protected String baseDataUrl;

    @Parameter(
            names = "--owner",
            description = "Owner for all stacks",
            required = true)
    protected String owner;

    @Parameter(
            names = "--project",
            description = "Project for all stacks",
            required = true)
    protected String project;

    public RenderDataClientParameters() {
        this.baseDataUrl = null;
        this.owner = null;
        this.project = null;
    }

}