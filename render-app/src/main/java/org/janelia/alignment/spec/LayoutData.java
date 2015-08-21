package org.janelia.alignment.spec;

import java.io.Serializable;

/**
 * Tile information from the layout file.
 *
 * @author Eric Trautman
 */
public class LayoutData implements Serializable {

    private final String sectionId;
    private final String temca;
    private final String camera;
    private final Integer imageRow;
    private final Integer imageCol;
    private final Double stageX;
    private final Double stageY;
    private final Double rotation;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private LayoutData() {
        this.sectionId = null;
        this.temca = null;
        this.camera = null;
        this.imageRow = null;
        this.imageCol = null;
        this.stageX = null;
        this.stageY = null;
        this.rotation = null;
    }

    public LayoutData(final String sectionId,
                      final String temca,
                      final String camera,
                      final Integer imageRow,
                      final Integer imageCol,
                      final Double stageX,
                      final Double stageY,
                      final Double rotation) {
        this.sectionId = sectionId;
        this.temca = temca;
        this.camera = camera;
        this.imageRow = imageRow;
        this.imageCol = imageCol;
        this.stageX = stageX;
        this.stageY = stageY;
        this.rotation = rotation;
    }

    public String getSectionId() {
        return sectionId;
    }

    public String getTemca() {
        return temca;
    }

    public String getCamera() {
        return camera;
    }

    public Integer getImageRow() {
        return imageRow;
    }

    public Integer getImageCol() {
        return imageCol;
    }

    public Double getStageX() {
        return stageX;
    }

    public Double getStageY() {
        return stageY;
    }

    public Double getRotation() {
        return rotation;
    }
}
