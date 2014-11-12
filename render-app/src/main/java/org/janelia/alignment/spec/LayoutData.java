package org.janelia.alignment.spec;

/**
 * Tile information from the layout file.
 *
 * @author Eric Trautman
 */
public class LayoutData {

    private Integer sectionId;
    private String temca;
    private String camera;
    private String imageRow;
    private String imageCol;
    private Double stageX;
    private Double stageY;
    private Double rotation;

    public LayoutData(Integer sectionId,
                      String temca,
                      String camera,
                      String imageRow,
                      String imageCol,
                      Double stageX,
                      Double stageY,
                      Double rotation) {
        this.sectionId = sectionId;
        this.temca = temca;
        this.camera = camera;
        this.imageRow = imageRow;
        this.imageCol = imageCol;
        this.stageX = stageX;
        this.stageY = stageY;
        this.rotation = rotation;
    }

    public Integer getSectionId() {
        return sectionId;
    }

    public String getTemca() {
        return temca;
    }

    public String getCamera() {
        return camera;
    }

    public String getImageRow() {
        return imageRow;
    }

    public String getImageCol() {
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
