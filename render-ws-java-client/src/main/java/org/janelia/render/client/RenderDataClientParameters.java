package org.janelia.render.client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.janelia.alignment.json.JsonUtils;

/**
 * Base parameters for all render web service clients.
 *
 * @author Eric Trautman
 */
@Parameters
public class RenderDataClientParameters {

    @Parameter(
            names = "--help",
            description = "Display this note",
            help = true)
    protected transient boolean help;

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

    private transient JCommander jCommander;

    public RenderDataClientParameters() {
        this.help = false;
        this.baseDataUrl = null;
        this.owner = null;
        this.project = null;
        this.jCommander = null;
    }

    public void parse(final String[] args) throws IllegalArgumentException {

        jCommander = new JCommander(this);
        jCommander.setProgramName("java -cp current-ws-standalone.jar " + this.getClass().getName());

        boolean parseFailed = true;
        try {
            jCommander.parse(args);
            parseFailed = false;
        } catch (final Throwable t) {
            JCommander.getConsole().println("\nERROR: failed to parse command line arguments\n\n" + t.getMessage());
        }

        if (help || parseFailed) {
            JCommander.getConsole().println("");
            jCommander.usage();
            System.exit(1);
        }
    }

    public RenderDataClient getClient() {
        return new RenderDataClient(baseDataUrl, owner, project);
    }

    public RenderDataClient getClient(final String project) {
        return new RenderDataClient(baseDataUrl, owner, project);
    }

    /**
     * @return string representation of these parameters.
     */
    @Override
    public String toString() {
        try {
            return JsonUtils.MAPPER.writeValueAsString(this);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

}