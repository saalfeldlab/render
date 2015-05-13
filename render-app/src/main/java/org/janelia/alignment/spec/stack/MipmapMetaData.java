package org.janelia.alignment.spec.stack;

import java.io.Serializable;

/**
 * Meta data about the mipmaps generated for a stack.
 *
 * Thinking we might be able to use this (or something like it) to
 * derive mipmap paths rather than store them explicitly in tile specs.
 *
 * @author Eric Trautman
 */
public class MipmapMetaData
        implements Serializable {

    private final String rootPath;
    private final Integer numberOfLevels;

    public MipmapMetaData() {
        this(null, null);
    }

    public MipmapMetaData(final String rootPath,
                          final Integer numberOfLevels) {
        this.rootPath = rootPath;
        this.numberOfLevels = numberOfLevels;
    }

    public String getRootPath() {
        return rootPath;
    }

    public Integer getNumberOfLevels() {
        return numberOfLevels;
    }
}
