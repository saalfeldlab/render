package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.Utils;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;

/**
 * Parameters for rendering tiles to storage.
 */
public class TileRenderParameters
        implements Serializable {

    public enum RenderType {
        EIGHT_BIT, SIXTEEN_BIT, ARGB
    }

    @Parameter(
            names = "--rootDirectory",
            description = "Root directory for rendered tiles (e.g. /nrs/flyem/render/tiles)",
            required = true)
    public String rootDirectory;

    @Parameter(
            names = "--runTimestamp",
            description = "Run timestamp to use in directory path for rendered tiles (e.g. 20220830_093700).  " +
                          "Omit to use calculated timestamp.  " +
                          "Include for array jobs to ensure all tiles are rendered under same base path")
    public String runTimestamp;

    @Parameter(
            names = "--scale",
            description = "Scale for each rendered tile"
    )
    public Double scale = 1.0;

    @Parameter(
            names = "--format",
            description = "Format for rendered tiles"
    )
    public String format = Utils.PNG_FORMAT;

    @Parameter(
            names = "--doFilter",
            description = "Use ad hoc filter to support alignment",
            arity = 0)
    public boolean doFilter = false;

    @Parameter(
            names = "--renderType",
            description = "How the tiles should be rendered")
    public RenderType renderType = RenderType.EIGHT_BIT;

    @Parameter(
            names = "--filterListName",
            description = "Apply this filter list to all rendering (overrides doFilter option)"
    )
    public String filterListName;

    @Parameter(
            names = "--channels",
            description = "Specify channel(s) and weights to render (e.g. 'DAPI' or 'DAPI__0.7__TdTomato__0.3')"
    )
    public String channels;

    @Parameter(
            names = "--fillWithNoise",
            description = "Fill image with noise before rendering to improve point match derivation",
            arity = 0)
    public boolean fillWithNoise = false;

    @Parameter(
            names = "--maxIntensity",
            description = "Max intensity to render image"
    )
    public Integer maxIntensity;

    @Parameter(
            names = "--minIntensity",
            description = "Min intensity to render image"
    )
    public Integer minIntensity;

    @Parameter(
            names = "--excludeMask",
            description = "Exclude tile masks when rendering",
            arity = 0)
    public boolean excludeMask = false;

    @Parameter(
            names = "--excludeAllTransforms",
            description = "Exclude all tile transforms when rendering",
            arity = 0)
    public boolean excludeAllTransforms = false;

    @Parameter(
            names = "--renderMaskOnly",
            description = "Only render transformed mask for each tile",
            arity = 0)
    public boolean renderMaskOnly = false;

    @Parameter(
            names = "--tileIds",
            description = "Explicit IDs for tiles to render",
            variableArity = true
    )
    public List<String> tileIds;

    @Parameter(
            names = "--tileIdPattern",
            description = "Only include tileIds that match this pattern (filters z based and explicit tile ids)"
    )
    public String tileIdPattern;

    @Parameter(
            names = "--hackStack",
            description = "If specified, create tile specs that reference the rendered tiles " +
                          "and save them to this stack.  The hackTransformCount determines how " +
                          "many transforms are rendered and how many are included in each tile spec.")
    public String hackStack;

    @Parameter(
            names = "--hackTransformCount",
            description = "Number of transforms to remove from the end of each tile spec's list " +
                          "during rendering but then include in the hack stack's tile specs")
    public Integer hackTransformCount;

    @Parameter(
            names = "--completeHackStack",
            description = "Complete the hack stack after saving all tile specs",
            arity = 0)
    public boolean completeHackStack = false;

    public String getRunTimestamp() {
        if (this.runTimestamp == null) {
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
            this.runTimestamp = sdf.format(new Date());
        }
        return this.runTimestamp;
    }

    public TileRenderParameters withTileIdPattern(final String tileIdPattern) {

        final TileRenderParameters tp = new TileRenderParameters();

        // copy everything ...
        tp.rootDirectory = this.rootDirectory;
        tp.runTimestamp = this.runTimestamp;
        tp.scale = this.scale;
        tp.format = this.format;
        tp.doFilter = this.doFilter;
        tp.renderType = this.renderType;
        tp.filterListName = this.filterListName;
        tp.channels = this.channels;
        tp.fillWithNoise = this.fillWithNoise;
        tp.maxIntensity = this.maxIntensity;
        tp.minIntensity = this.minIntensity;
        tp.excludeMask = this.excludeMask;
        tp.excludeAllTransforms = this.excludeAllTransforms;
        tp.renderMaskOnly = this.renderMaskOnly;
        tp.tileIds = this.tileIds;
        tp.tileIdPattern = this.tileIdPattern;
        tp.hackStack = this.hackStack;
        tp.hackTransformCount = this.hackTransformCount;
        tp.completeHackStack = this.completeHackStack;

        // and then overwrite the tileIdPattern
        tp.tileIdPattern = tileIdPattern;

        return tp;
    }

    public static TileRenderParameters fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    public static TileRenderParameters fromJsonFile(final String dataFile)
            throws IOException {
        final TileRenderParameters parameters;
        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            parameters = fromJson(reader);
        }
        return parameters;
    }

    public static TileRenderParameters buildMfovAsTileVersion(final String mfovRootDirectory,
                                                              final String runTimestamp,
                                                              final String hackStack) {

        final TileRenderParameters trp = new TileRenderParameters();

        trp.rootDirectory = mfovRootDirectory;
        trp.runTimestamp = runTimestamp;
        trp.scale = 1.0; // MFOV-as-tile specs have source URLs with scale=0.2, so they should be rendered full scale
        trp.format = Utils.PNG_FORMAT;
        trp.renderType = TileRenderParameters.RenderType.EIGHT_BIT;
        trp.hackStack = hackStack;
        trp.completeHackStack = false;

        trp.doFilter = false;
        trp.filterListName = null;
        trp.channels = null;
        trp.fillWithNoise = false;
        trp.maxIntensity = null;
        trp.minIntensity = null;
        trp.excludeMask = true;
        trp.excludeAllTransforms = true;
        trp.renderMaskOnly = false;
        trp.tileIds = null;
        trp.tileIdPattern = null; // ".*_m0052"
        trp.hackTransformCount = null;

        return trp;
    }

    private static final JsonUtils.Helper<TileRenderParameters> JSON_HELPER =
            new JsonUtils.Helper<>(TileRenderParameters.class);
}
