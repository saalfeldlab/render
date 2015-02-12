package org.janelia.render.client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import org.janelia.alignment.json.JsonUtils;

/**
 * Base parameters for all render service clients.
 *
 * @author Eric Trautman
 */
@Parameters
public class RenderDataClientParameters {

    @Parameter(names = "--help", description = "Display this note", help = true)
    private transient boolean help;

    @Parameter(names = "--baseDataUrl", description = "Base URL for data", required = true)
    private String baseDataUrl;

    @Parameter(names = "--owner", description = "Owner for all stacks", required = true)
    private String owner;

    @Parameter(names = "--project", description = "Project for all stacks", required = true)
    private String project;

    private transient JCommander jCommander;

    public RenderDataClientParameters() {
        this.help = false;
        this.baseDataUrl = null;
        this.owner = null;
        this.project = null;
        this.jCommander = null;
    }

    public void parse(String[] args) throws IllegalArgumentException {

        jCommander = new JCommander(this);
        jCommander.setProgramName("java -cp current-ws-standalone.jar " + this.getClass().getName());

        try {
            jCommander.parse(args);
        } catch (Throwable t) {
            throw new IllegalArgumentException("failed to parse command line arguments", t);
        }

        if (help) {
            jCommander.usage();
            System.exit(1);
        }
    }

    public RenderDataClient getClient() {
        return new RenderDataClient(baseDataUrl, owner, project);
    }

    /**
     * @return string representation of these parameters.
     */
    @Override
    public String toString() {
        return JsonUtils.GSON.toJson(this);
    }

}