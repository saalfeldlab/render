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
    private Integer imageRow;
    private Integer imageCol;
    private Double stageX;
    private Double stageY;
    private Double rotation;

    public LayoutData(Integer sectionId,
                      String temca,
                      String camera,
                      Integer imageRow,
                      Integer imageCol,
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

    /**
     * @return 32-bit "unique enough" tile id suitable for Karsh pipeline
     */
    public Integer getKarshTileId() {
        Integer karshTileId = null;
        if ((sectionId != null) && (imageCol != null) && (imageRow != null)) {
            karshTileId = (sectionId * 256 * 256) + (imageCol * 256) + imageRow;
        }
        return karshTileId;
    }

}
