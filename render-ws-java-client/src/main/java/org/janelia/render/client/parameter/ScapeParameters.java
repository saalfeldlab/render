package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.SectionData;

/**
 * Parameters for rendering montage scapes within a stack.
 */
public class ScapeParameters
        implements Serializable {

    @Parameter(
            names = "--rootDirectory",
            description = "Root directory for rendered layers (e.g. /groups/flyTEM/flyTEM/rendered_scapes)",
            required = true)
    public String rootDirectory;

    @Parameter(
            names = "--maxImagesPerDirectory",
            description = "Maximum number of images to render in one directory"
    )
    public Integer maxImagesPerDirectory = 1000;

    @Parameter(
            names = "--scale",
            description = "Scale for each rendered layer"
    )
    public Double scale = 0.02;

    @Parameter(
            names = "--scaledScapeSize",
            description = "If specified, set the rendered layer scale so that the longer dimension (height or width) " +
                          "of each rendered scape is this many pixels.  This will override the --scale option."
    )
    public Integer scaledScapeSize;

    @Parameter(
            names = "--zScale",
            description = "Ratio of z to xy resolution for creating isotropic layer projections (omit to skip projection)"
    )
    public Double zScale;

    @Parameter(
            names = "--format",
            description = "Format for rendered boxes"
    )
    public String format = Utils.JPEG_FORMAT;

    @Parameter(
            names = "--resolutionUnit",
            description = "If specified (e.g. as 'nm') and format is tiff, " +
                          "include resolution data in rendered tiff headers.  ")
    public String resolutionUnit;

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
            names = "--channels",
            description = "Specify channel(s) and weights to render (e.g. 'DAPI' or 'DAPI__0.7__TdTomato__0.3')"
    )
    public String channels;

    @Parameter(
            names = "--fillWithNoise",
            description = "Fill image with noise before rendering to improve point match derivation"
    )
    public boolean fillWithNoise = false;

    @Parameter(
            names = "--useLayerBounds",
            description = "Base each scape on layer bounds instead of on stack bounds (e.g. for unaligned data)",
            arity = 1)
    public boolean useLayerBounds = false;

    @Parameter(
            names = "--minX",
            description = "Left most pixel coordinate in world coordinates.  Default is minX of stack (or layer when --useLayerBounds true)"
    )
    public Double minX;

    @Parameter(
            names = "--minY",
            description = "Top most pixel coordinate in world coordinates.  Default is minY of stack (or layer when --useLayerBounds true)"
    )
    public Double minY;

    @Parameter(
            names = "--width",
            description = "Width in world coordinates.  Default is maxX - minX of stack (or layer when --useLayerBounds true)"
    )
    public Double width;

    @Parameter(
            names = "--height",
            description = "Height in world coordinates.  Default is maxY - minY of stack (or layer when --useLayerBounds true)"
    )
    public Double height;

    @Parameter(
            names = "--hackStackSuffix",
            description = "If specified, create tile specs that reference the rendered scape images and save them to " +
                          "a new 'hack' stack.  The hack stack will have the source stack's owner and project and " +
                          "its name will be the source stack's name with this suffix.")
    public String hackStackSuffix;

    public ScapeParameters() {
    }

    public File getSectionRootDirectory(final String project,
                                        final String stack) {

        final String scapeDir = "scape_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        final Path sectionRootPath = Paths.get(rootDirectory,
                                               project,
                                               stack,
                                               scapeDir).toAbsolutePath();
        return sectionRootPath.toFile();
    }

    public double getEffectiveBound(final Double layerValue,
                                    final Double stackValue,
                                    final Double parameterValue) {
        final double value;
        if (parameterValue == null) {
            if (useLayerBounds) {
                value = layerValue;
            } else {
                value = stackValue;
            }
        } else {
            value = parameterValue;
        }
        return value;
    }

    public Double getMaxX(final double effectiveMinX) {
        return (width == null) ? null : effectiveMinX + width;
    }

    public Double getMaxY(final double effectiveMinY) {
        return (height == null) ? null : effectiveMinY + height;
    }

    public double getScapeRenderScale(final Bounds stackBounds,
                                      final List<SectionData> sectionDataList) {
        double scapeRenderScale = scale;
        if (scaledScapeSize != null) {
            final int maxSize;
            if (useLayerBounds) {
                maxSize = sectionDataList.stream()
                        .map(sd -> Math.max(sd.getWidth(), sd.getHeight()))
                        .max(Integer::compareTo).orElse(0);
            } else {
                maxSize = Math.max(stackBounds.getWidth(), stackBounds.getHeight());
            }
            scapeRenderScale = scaledScapeSize / (double) maxSize;
        }
        return scapeRenderScale;

    }

    public boolean isHackStackSuffixDefined() {
        return hackStackSuffix != null && ! hackStackSuffix.trim().isEmpty();
    }
}
