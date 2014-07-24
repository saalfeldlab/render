package org.janelia.render.client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

/**
 * Render client command line parameters.
 *
 * @author Eric Trautman
 */
@Parameters
public class RenderClientParameters {

    public static final String JPEG_FORMAT = "jpeg";
    public static final String PNG_FORMAT = "png";

    @Parameter(names = "--help",
               description = "Display this note",
               help = true)
    private transient boolean help;

    @Parameter(names = "--baseUri",
               description = "Base URI for web services requests",
               required = true)
    private String baseUri;

    @Parameter( names = "--projectId",
                description = "Project ID",
                required = true)
    private String projectId;

    @Parameter(names = "--in",
               description = "Path to render parameters json file",
               required = true)
    private String in;

    @Parameter(names = "--out",
               description = "Path for the output image file (if omitted, image will be displayed in window)",
               required = false)
    private String out;

    @Parameter(names = "--format",
               description = "Format for output image (jpeg or png, default is jpeg)",
               required = false)
    private String format;

    private transient JCommander jCommander;

    public RenderClientParameters() {
        this.help = false;
        this.baseUri = null;
        this.projectId = null;
        this.in = null;
        this.out = null;
        this.format = JPEG_FORMAT;
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
    public static RenderClientParameters parseCommandLineArgs(String[] args) throws IllegalArgumentException {
        RenderClientParameters parameters = new RenderClientParameters();
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

    public String getBaseUri() {
        return baseUri;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getIn() {
        return in;
    }

    public String getOut() {
        return out;
    }

    public String getFormat() {
        return format;
    }

    public boolean renderInWindow() {
        return (out == null);
    }

    @Override
    public String toString() {
        return "RenderClientParameters{baseUri='" + baseUri + '\'' +
               ", projectId='" + projectId + '\'' +
               ", in='" + in + '\'' +
               ", out='" + out + '\'' +
               ", format='" + format + '\'' +
               '}';
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

    private void setCommander() {
        jCommander = new JCommander(this);
        jCommander.setProgramName("java -jar render-ws-client.jar");
    }
}
