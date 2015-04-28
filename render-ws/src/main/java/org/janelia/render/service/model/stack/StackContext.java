package org.janelia.render.service.model.stack;

import java.io.Serializable;

/**
 * Stack information that is common for all versions.
 *
 * @author Eric Trautman
 */
public class StackContext
        implements Serializable {

    private final Integer iteration;
    private final Integer step;
    private final Double xResolution;
    private final Double yResolution;
    private final Double zResolution;
    private final String archiveRootPath;
    private final MipmapMetaData mipmapMetaData;

    public StackContext(Integer iteration,
                        Integer step,
                        Double xResolution,
                        Double yResolution,
                        Double zResolution,
                        String archiveRootPath,
                        MipmapMetaData mipmapMetaData) {
        this.iteration = iteration;
        this.step = step;
        this.xResolution = xResolution;
        this.yResolution = yResolution;
        this.zResolution = zResolution;
        this.archiveRootPath = archiveRootPath;
        this.mipmapMetaData = mipmapMetaData;
    }

    public Integer getIteration() {
        return iteration;
    }

    public Integer getStep() {
        return step;
    }

    public Double getxResolution() {
        return xResolution;
    }

    public Double getyResolution() {
        return yResolution;
    }

    public Double getzResolution() {
        return zResolution;
    }

    public String getArchiveRootPath() {
        return archiveRootPath;
    }

    public boolean isArchiveEnabled() {
        return (archiveRootPath != null);
    }

    public MipmapMetaData getMipmapMetaData() {
        return mipmapMetaData;
    }
}
