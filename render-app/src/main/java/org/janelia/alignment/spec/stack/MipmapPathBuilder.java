package org.janelia.alignment.spec.stack;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Map;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.ApiModelProperty;

/**
 * <p>
 *     Supports dynamic derivation of image and mask mipmap paths based upon a common root path.
 *     A single builder instance can be stored for a stack and then shared for all tiles in the stack.
 *     This means that detailed path information does not need to be stored for each tile.
 *     It also allows mipmaps to be added or removed without requiring changes to stored tile specifications.
 * </p>
 *
 * <p>
 *     Derived paths have the form:
 * <pre>
 *         [root path]/[level]/[source (level 0) path].[extension]
 * </pre>
 *
 *     For example,
 * <pre>
 *     Given:
 *         root path:   /tier2/flyTEM/mipmaps
 *         extension:   png
 *         source file: /groups/flyTEM/data/row2col3.tif
 *
 *     The derived level 2 path would be:
 *         /tier2/flyTEM/mipmaps/2/groups/flyTEM/data/row2col3.tif.png
 * </pre>
 * </p>
 *
 * <p>
 *     The source file paths are include to prevent collisions in derived paths.
 *     The level is placed before the source file path to reduce the number of files in any one directory.
 * </p>
 *
 * @author Eric Trautman
 */
public class MipmapPathBuilder
        implements Serializable {

    private final String rootPath;
    private final Integer numberOfLevels;
    private final String extension;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private MipmapPathBuilder() {
        this.rootPath = null;
        this.numberOfLevels = null;
        this.extension = null;
    }

    public MipmapPathBuilder(final String rootPath,
                             final Integer numberOfLevels,
                             final String extension) throws IllegalArgumentException {

        if (rootPath == null) {
            throw new IllegalArgumentException("rootPath must be specified for MipmapPathBuilder");
        } else if (rootPath.endsWith("/")) {
            this.rootPath = rootPath;
        } else {
            this.rootPath = rootPath + '/';
        }

        if (numberOfLevels == null) {
            throw new IllegalArgumentException("numberOfLevels must be specified for MipmapPathBuilder");
        } else {
            this.numberOfLevels = numberOfLevels;
        }

        if (extension == null) {
            throw new IllegalArgumentException("extension must be specified for MipmapPathBuilder");
        } else {
            this.extension = extension;
        }
    }

    @ApiModelProperty(
            value = "root path for all mipmaps",
            notes = "The mipmap builder directory tree should be organized like <mipmap-root-path>/<level>/<level-0-path>.  " +
                    "A typical setup for the <mipmap-root-path> is <base-directory>/rendered_mipmaps/<stack-owner>/<stack-project> " +
                    "(e.g. /nrs/flyTEM/rendered_mipmaps/flyTEM/FAFB00).")
    public String getRootPath() {
        return rootPath;
    }

    public Integer getNumberOfLevels() {
        return numberOfLevels;
    }

    @ApiModelProperty(
            value = "file extension (without dot) for all mipmaps",
            allowableValues = "tif, jpg, png")
    public String getExtension() {
        return extension;
    }

    public boolean hasSamePathAndExtension(final MipmapPathBuilder that) {
        return this.rootPath.equals(that.rootPath) && this.extension.equals(that.extension);
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static MipmapPathBuilder fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    @Override
    public String toString() {
        return toJson();
    }

    public Map.Entry<Integer, ImageAndMask> deriveImageAndMask(final Integer mipmapLevel,
                                                               final Map.Entry<Integer, ImageAndMask> sourceEntry,
                                                               final boolean validate) {
        Integer derivedLevel = numberOfLevels;
        if (mipmapLevel < derivedLevel) {
            derivedLevel = mipmapLevel;
        }

        final ImageAndMask sourceImageAndMask = sourceEntry.getValue();

        final String derivedImageUrl = deriveMipmapUrl(sourceImageAndMask.getImageUrl(), derivedLevel);

        String derivedMaskUrl = null;
        if (sourceImageAndMask.hasMask()) {
            derivedMaskUrl = deriveMipmapUrl(sourceImageAndMask.getMaskUrl(), derivedLevel);
        }

        final ImageAndMask derivedImageAndMask = new ImageAndMask(derivedImageUrl, derivedMaskUrl);

        Map.Entry<Integer, ImageAndMask> derivedEntry;
        try {
            if (validate) {
                derivedImageAndMask.validate();
            }
            derivedEntry = new AbstractMap.SimpleEntry<>(derivedLevel, derivedImageAndMask);
        } catch (final Throwable t) {
            LOG.warn("derived imageAndMask is not valid, reverting to source", t);
            derivedEntry = sourceEntry;
        }

        return derivedEntry;
    }

    private String deriveMipmapUrl(final String urlString,
                                   final int derivedLevel) {

        final StringBuilder sb = new StringBuilder(256);
        sb.append(rootPath);
        sb.append(derivedLevel);

        final int colonIndex = urlString.indexOf(':');
        if (colonIndex > -1) {
            sb.append(urlString.substring(colonIndex + 1));
        } else if (urlString.startsWith("/")) {
            sb.append(urlString);
        } else {
            sb.append('/');
            sb.append(urlString);
        }

        sb.append('.');
        sb.append(extension);

        return sb.toString();
    }

    private static final Logger LOG = LoggerFactory.getLogger(MipmapPathBuilder.class);

    private static final JsonUtils.Helper<MipmapPathBuilder> JSON_HELPER =
            new JsonUtils.Helper<>(MipmapPathBuilder.class);
}
