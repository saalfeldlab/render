package org.janelia.alignment.spec.stack;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Standard naming pattern for Janelia FIB-SEM volumes where 8-bit mipmap data
     * is aggregated as separate data sets within an HDF5 container.
     * For example:
     * <pre>
     *     level 0: file:///data/Merlin-6257_21-05-20_125416.uint8.h5?dataSet=0-0-0.mipmap.0&z=0
     *     level 1: file:///data/Merlin-6257_21-05-20_125416.uint8.h5?dataSet=0-0-0.mipmap.1&z=0
     * </pre>
     */
    public static final String JANELIA_FIBSEM_H5_MIPMAP_PATTERN_STRING =
            "(.*dataSet=\\d+-\\d+-\\d+\\.mipmap\\.)\\d+(.*)";

    private final String rootPath;
    private final Integer numberOfLevels;
    private final String extension;
    private final String imageMipmapPatternString;

    private transient Pattern imageMipmapPattern;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private MipmapPathBuilder() {
        this.rootPath = null;
        this.numberOfLevels = null;
        this.extension = null;
        this.imageMipmapPatternString = null;
    }

    public MipmapPathBuilder(final String rootPath,
                             final Integer numberOfLevels,
                             final String extension,
                             final String imageMipmapPatternString) throws IllegalArgumentException {

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

        this.imageMipmapPatternString = imageMipmapPatternString;
    }

    @ApiModelProperty(
            value = "root path for all mipmaps",
            required = true,
            notes = "The mipmap builder directory tree should be organized like <mipmap-root-path>/<level>/<level-0-path>.  " +
                    "A typical setup for the <mipmap-root-path> is <base-directory>/rendered_mipmaps/<stack-owner>/<stack-project> " +
                    "(e.g. /nrs/flyTEM/rendered_mipmaps/flyTEM/FAFB00).")
    public String getRootPath() {
        return rootPath;
    }

    @ApiModelProperty(
            value = "number of mipmap levels built",
            required = true)
    public Integer getNumberOfLevels() {
        return numberOfLevels;
    }

    @ApiModelProperty(
            value = "file extension (without dot) for all mipmaps",
            required = true,
            allowableValues = "tif, jpg, png")
    public String getExtension() {
        return extension;
    }

    /** For example see {@link MipmapPathBuilder#JANELIA_FIBSEM_H5_MIPMAP_PATTERN_STRING} */
    @ApiModelProperty(
            value = "pattern for deriving image mipmap URLs",
            notes = "If specified, overrides use of root path when deriving mipmap URLs for images " +
                    "(mask behavior remains unchanged).  The pattern is intended for use with HDF5 " +
                    "collections where mipmap levels are stored as separate data sets in the same file.  " +
                    "The pattern is expected to have two groups, a prefix group and a suffix group, with " +
                    "the mipmap level to be changed in between.")
    public String getImageMipmapPatternString() {
        return imageMipmapPatternString;
    }

    @JsonIgnore
    public Pattern getImageMipmapPattern() {
        if ((imageMipmapPattern == null) && (imageMipmapPatternString != null)) {
            imageMipmapPattern = Pattern.compile(imageMipmapPatternString);
        }
        return imageMipmapPattern;
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
        Map.Entry<Integer, ImageAndMask> derivedEntry = sourceEntry;

        Integer derivedLevel = numberOfLevels;
        if (mipmapLevel < derivedLevel) {
            derivedLevel = mipmapLevel;
        }

        final ImageAndMask sourceImageAndMask = sourceEntry.getValue();

        final String derivedImageUrl;
        if (imageMipmapPatternString == null) {

            derivedImageUrl = deriveMipmapUrl(sourceImageAndMask.getImageUrl(), derivedLevel);

        } else {

            final String imageUrl = sourceImageAndMask.getImageUrl();
            final Matcher m = getImageMipmapPattern().matcher(imageUrl);
            if (m.matches()) {
                if (m.groupCount() == 2) {
                    derivedImageUrl = m.group(1) + derivedLevel + m.group(2);
                } else {
                    LOG.warn("imageMipmapPatternString '{}' does not define prefix and suffix groups, reverting to source",
                             imageMipmapPatternString);
                    derivedImageUrl = null;
                }
            } else {
                LOG.warn("imageUrl {} does not match mipmap pattern, reverting to source", imageUrl);
                derivedImageUrl = null;
            }

        }

        if (derivedImageUrl != null) {

            String derivedMaskUrl = null;
            if (sourceImageAndMask.hasMask()) {
                derivedMaskUrl = deriveMipmapUrl(sourceImageAndMask.getMaskUrl(), derivedLevel);
            }

            final ImageAndMask derivedImageAndMask = sourceImageAndMask.copyWithDerivedUrls(derivedImageUrl,
                                                                                            derivedMaskUrl);

            try {
                if (validate) {
                    derivedImageAndMask.validate();
                }
                derivedEntry = new AbstractMap.SimpleEntry<>(derivedLevel, derivedImageAndMask);
            } catch (final Throwable t) {
                LOG.warn("derived imageAndMask is not valid, reverting to source", t);
            }

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
