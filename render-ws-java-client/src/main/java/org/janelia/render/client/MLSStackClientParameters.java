package org.janelia.render.client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import org.janelia.alignment.MipmapGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Parameters for MLS stack generation.
 *
 * @author Eric Trautman
 */
@Parameters
public class MLSStackClientParameters {

    @Parameter(names = "--help", description = "Display this note", help = true)
    private transient boolean help;

    @Parameter(names = "--owner", description = "Owner for all stacks", required = true)
    private String owner;

    @Parameter(names = "--project", description = "Project for all stacks", required = true)
    private String project;

    @Parameter(names = "--alignStack", description = "Align stack name", required = true)
    private String alignStack;

    @Parameter(names = "--montageStack", description = "Montage stack name", required = true)
    private String montageStack;

    @Parameter(names = "--mlsStack", description = "Moving least squares stack name", required = true)
    private String mlsStack;

    @Parameter(names = "--baseDataUrl", description = "Base URL for data", required = true)
    private String baseDataUrl;

    @Parameter(names = "--alpha", description = "Alpha value for MLS transform", required = false)
    private Double alpha;

    @Parameter(description = "Z values", required = true)
    private List<String> zValues;

    private transient JCommander jCommander;

    public MLSStackClientParameters() {
        this.help = false;
        this.owner = null;
        this.project = null;
        this.alignStack = null;
        this.montageStack = null;
        this.mlsStack = null;
        this.baseDataUrl = null;
        this.alpha = null;
        this.zValues = new ArrayList<String>();

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
    public static MLSStackClientParameters parseCommandLineArgs(String[] args) throws IllegalArgumentException {
        MLSStackClientParameters parameters = new MLSStackClientParameters();
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

    public String getMontageStack() {
        return montageStack;
    }

    public String getMlsStack() {
        return mlsStack;
    }

    public String getBaseDataUrl() {
        return baseDataUrl;
    }

    public Double getAlpha() {
        return alpha;
    }

    public List<String> getzValues() {
        return zValues;
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
               ", montageStack='" + montageStack + '\'' +
               ", mlsStack='" + mlsStack + '\'' +
               ", baseDataUrl='" + baseDataUrl + '\'' +
               ", alpha=" + alpha +
               '}';
    }

    private void setCommander() {
        jCommander = new JCommander(this);
        jCommander.setProgramName("java -cp render-app.jar " + MipmapGenerator.class.getName());
    }

}
