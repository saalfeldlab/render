package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;
import java.util.Comparator;

import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.TileSpec;

/**
 * Parameters for rendering box images to disk.
 *
 * @author Eric Trautman
 */
public class MaterializedBoxParameters implements Serializable {

    @Parameter(
            names = "--stack",
            description = "Stack name",
            required = true)
    public String stack;

    @Parameter(
            names = "--rootDirectory",
            description = "Root directory for rendered tiles (e.g. /tier2/flyTEM/nobackup/rendered_boxes)",
            required = true)
    public String rootDirectory;

    @Parameter(
            names = "--width",
            description = "Width of each box",
            required = true)
    public Integer width;

    @Parameter(
            names = "--height",
            description = "Height of each box",
            required = true)
    public Integer height;

    @Parameter(
            names = "--maxLevel",
            description = "Maximum mipmap level to generate"
    )
    public Integer maxLevel = 0;

    @Parameter(
            names = "--format",
            description = "Format for rendered boxes"
    )
    public String format = Utils.PNG_FORMAT;

    @Parameter(
            names = "--maxOverviewWidthAndHeight",
            description = "Max width and height of layer overview image (omit or set to zero to disable overview generation)"
    )
    public Integer maxOverviewWidthAndHeight;

    @Parameter(
            names = "--doFilter",
            description = "Use ad hoc filter to support alignment"
    )
    public boolean doFilter = false;

    @Parameter(
            names = "--filterListName",
            description = "Apply this filter list to all rendering (overrides doFilter option)"
    )
    public String filterListName;

    @Parameter(
            names = "--skipInterpolation",
            description = "skip interpolation (e.g. for DMG data)",
            arity = 0)
    public boolean skipInterpolation = false;

    @Parameter(
            names = "--binaryMask",
            description = "use binary mask (e.g. for DMG data)",
            arity = 0)
    public boolean binaryMask = false;

    @Parameter(
            names = "--label",
            description = "Generate single color tile labels instead of actual tile images",
            arity = 0)
    public boolean label = false;

    @Parameter(
            names = "--createIGrid",
            description = "create an IGrid file",
            arity = 0)
    public boolean createIGrid = false;

    @Parameter(
            names = "--forceGeneration",
            description = "Regenerate boxes even if they already exist",
            arity = 0)
    public boolean forceGeneration = false;

    @Parameter(
            names = "--renderGroup",
            description = "Index (1-n) that identifies portion of layer to render (omit if only one job is being used)"
    )
    public Integer renderGroup;

    @Parameter(
            names = "--numberOfRenderGroups",
            description = "Total number of parallel jobs being used to render this layer (omit if only one job is being used)"
    )
    public Integer numberOfRenderGroups;

    @Parameter(
            names = "--sortByClusterGroupId",
            description = "Sort tile specs by cluster groupId so that larger clusters are rendered on top of smaller clusters"
    )
    public boolean sortByClusterGroupId = false;

    public boolean isOverviewNeeded() {
        return ((maxOverviewWidthAndHeight != null) && (maxOverviewWidthAndHeight > 0));
    }

    public MaterializedBoxParameters getInstanceForRenderGroup(final int group,
                                                               final int numberOfGroups) {
        final MaterializedBoxParameters p = new MaterializedBoxParameters();

        p.stack = this.stack;
        p.rootDirectory = this.rootDirectory;
        p.width = this.width;
        p.height = this.height;
        p.maxLevel = this.maxLevel;
        p.format = this.format;
        p.maxOverviewWidthAndHeight = this.maxOverviewWidthAndHeight;
        p.skipInterpolation = this.skipInterpolation;
        p.binaryMask = this.binaryMask;
        p.label = this.label;
        p.createIGrid = this.createIGrid;
        p.forceGeneration = this.forceGeneration;

        p.renderGroup = group;
        p.numberOfRenderGroups = numberOfGroups;

        return p;
    }

    /**
     * Orders tile specs by groupId descending and tileId ascending.
     * Specs with groupIds are ordered before specs without groupIds.
     *
     * Cluster groupIds are expected to be in reverse order by size with
     * the largest cluster having the least lexical groupId.
     *
     * This supports rendering smaller clusters before larger clusters,
     * ensuring that larger clusters are "on top" in cases where clusters intersect.
     */
    public static final Comparator<TileSpec> CLUSTER_GROUP_ID_COMPARATOR = (o1, o2) -> {
        int result = 0;
        if (o1.getGroupId() == null) {
            if (o2.getGroupId() != null) {
                result = 1;
            }
        } else if (o2.getGroupId() == null) {
            result = -1;
        } else {
            result = o2.getGroupId().compareTo(o1.getGroupId());
        }
        if (result == 0) {
            result = o1.getTileId().compareTo(o2.getTileId());
        }
        return result;
    };


}
