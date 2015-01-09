package org.janelia.render.client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

/**
 * Parameters for Align stack generation.
 *
 * @author Eric Trautman
 */
@Parameters
public class AlignStackClientParameters {

    @Parameter(names = "--help", description = "Display this note", help = true)
    private transient boolean help;

    @Parameter(names = "--owner", description = "Owner for all stacks", required = true)
    private String owner;

    @Parameter(names = "--project", description = "Project for all stacks", required = true)
    private String project;

    @Parameter(names = "--acquireStack", description = "Acquire stack name", required = true)
    private String acquireStack;

    @Parameter(names = "--alignStack", description = "Align stack name", required = true)
    private String alignStack;

    @Parameter(names = "--baseDataUrl", description = "Base URL for data", required = true)
    private String baseDataUrl;

    @Parameter(names = "--metFile", description = "MET file for layer", required = true)
    private String metFile;

    private transient JCommander jCommander;

    public AlignStackClientParameters() {
        this.help = false;
        this.owner = null;
        this.project = null;
        this.alignStack = null;
        this.acquireStack = null;
        this.baseDataUrl = null;
        this.metFile = null;

        this.jCommander = null;
    }

    /**
     * @param  args  arguments to parse.
     *
     * @return parameters instance populated by parsing the specified arguments.
     *
     * @throws IllegalArgumentException
     *   if any invalid arguments are specified.
     */
    public static AlignStackClientParameters parseCommandLineArgs(String[] args) throws IllegalArgumentException {
        AlignStackClientParameters parameters = new AlignStackClientParameters();
        parameters.setCommander();
        try {
            parameters.jCommander.parse(args);
        } catch (Throwable t) {
            throw new IllegalArgumentException("failed to parse command line arguments", t);
        }
        return parameters;
    }

    public boolean displayHelp() {
        return help;
    }

    public String getOwner() {
        return owner;
    }

    public String getProject() {
        return project;
    }

    public String getAlignStack() {
        return alignStack;
    }

    public String getAcquireStack() {
        return acquireStack;
    }

    public String getBaseDataUrl() {
        return baseDataUrl;
    }

    public String getMetFile() {
        return metFile;
    }

    /**
     * Displays command usage information on the console (standard-out).
     */
    public void showUsage() {
        if (jCommander == null) {
            setCommander();
        }
        jCommander.usage();
    }

    /**
     * @return string representation of these parameters.
     */
    @Override
    public String toString() {
        return "{owner='" + owner + '\'' +
               ", project='" + project + '\'' +
               ", alignStack='" + alignStack + '\'' +
               ", acquireStack='" + acquireStack + '\'' +
               ", baseDataUrl='" + baseDataUrl + '\'' +
               ", metFile=" + metFile +
               '}';
    }

    private void setCommander() {
        jCommander = new JCommander(this);
        jCommander.setProgramName("java -cp current-ws-standalone.jar " + AlignStackClient.class.getName());
    }

}
