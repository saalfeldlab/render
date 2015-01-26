package org.janelia.render.client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.Utils;

/**
 * Parameters for box client invocations.
 *
 * @author Eric Trautman
 */
@Parameters
public class BoxClientParameters {

    @Parameter(names = "--help", description = "Display this note", help = true)
    private transient boolean help;

    @Parameter(names = "--owner", description = "Stack owner", required = true)
    private String owner;

    @Parameter(names = "--project", description = "Stack project", required = true)
    private String project;

    @Parameter(names = "--stack", description = "Stack name", required = true)
    private String stack;

    @Parameter(names = "--baseDataUrl", description = "Base URL for data", required = true)
    private String baseDataUrl;

    @Parameter(names = "--rootDirectory", description = "Root directory for rendered tiles (e.g. /tier2/flyTEM/nobackup/rendered_boxes/fly_pilot/20141216_863_align)", required = true)
    private String rootDirectory;

    @Parameter(names = "--width", description = "Width of each box", required = true)
    private Integer width;

    @Parameter(names = "--height", description = "Height of each box", required = true)
    private Integer height;

    @Parameter(names = "--maxLevel", description = "Maximum mipmap level to generate (default is 0)", required = false)
    private Integer maxLevel;

    @Parameter(names = "--format", description = "Format for rendered boxes (default is PNG)", required = false)
    private String format;

    @Parameter(names = "--overviewWidth", description = "Width of layer overview image (omit or set to zero to disable overview generation)", required = false)
    private Integer overviewWidth;

    @Parameter(names = "--skipInterpolation", description = "skip interpolation (e.g. for DMG data)", required = false, arity = 0)
    public boolean skipInterpolation;

    @Parameter(names = "--label", description = "Generate single color tile labels instead of actual tile images", required = false, arity = 0)
    private boolean label;

    @Parameter(description = "Z values for layers to render", required = true)
    private List<Double> zValues;

    private transient JCommander jCommander;

    public BoxClientParameters() {
        this.help = false;
        this.owner = null;
        this.project = null;
        this.stack = null;
        this.baseDataUrl = null;
        this.rootDirectory = null;
        this.width = null;
        this.height = null;
        this.maxLevel = 0;
        this.format = Utils.PNG_FORMAT;
        this.overviewWidth = null;
        this.skipInterpolation = false;
        this.label = false;
        this.zValues = new ArrayList<Double>();

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
    public static BoxClientParameters parseCommandLineArgs(String[] args) throws IllegalArgumentException {
        BoxClientParameters parameters = new BoxClientParameters();
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

    public String getStack() {
        return stack;
    }

    public String getBaseDataUrl() {
        return baseDataUrl;
    }

    public String getRootDirectory() {
        return rootDirectory;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Integer getMaxLevel() {
        return maxLevel;
    }

    public String getFormat() {
        return format;
    }

    public Integer getOverviewWidth() {
        return overviewWidth;
    }

    public boolean isSkipInterpolation() {
        return skipInterpolation;
    }

    public boolean isLabel() {
        return label;
    }

    public boolean isOverviewNeeded() {
        return ((overviewWidth != null) && (overviewWidth > 0));
    }

    public List<Double> getzValues() {
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

    @Override
    public String toString() {
        return "{owner: '" + owner + '\'' +
               ", project: '" + project + '\'' +
               ", stack: " + stack + '\'' +
               ", baseDataUrl: '" + baseDataUrl + '\'' +
               ", rootDirectory: '" + rootDirectory + '\'' +
               ", width: " + width +
               ", height: " + height +
               ", maxLevel: " + maxLevel +
               ", format: '" + format + '\'' +
               ", overviewWidth: '" + overviewWidth + '\'' +
               '}';
    }

    private void setCommander() {
        jCommander = new JCommander(this);
        jCommander.setProgramName("java -cp current-ws-standalone.jar " + BoxClient.class.getName());
    }

}
