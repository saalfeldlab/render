package org.janelia.alignment.spec;

import java.io.Serializable;

import io.swagger.annotations.ApiModelProperty;

/**
 * Tile information from the layout file (or acquisition system).
 *
 * @author Eric Trautman
 */
public class LayoutData implements Serializable {

    @ApiModelProperty(value = "Immutable section identifier for tile (typically text form of z value from acquisition system)")
    private final String sectionId;

    @ApiModelProperty(value = "Identifies camera array used for tile acquisition")
    private final String temca;

    @ApiModelProperty(value = "Identifies camera used for tile acquisition")
    private final String camera;

    @ApiModelProperty(value = "Tile row from grid-based acquisition system")
    private final Integer imageRow;

    @ApiModelProperty(value = "Tile column from grid-based acquisition system")
    private final Integer imageCol;

    @ApiModelProperty(value = "Acquisition system's X location of tile (in pixels)")
    private final Double stageX;

    @ApiModelProperty(value = "Acquisition system's Y location of tile (in pixels)")
    private final Double stageY;

    @ApiModelProperty(value = "Angle of camera for tile acquisition (in units of choice)")
    private final Double rotation;

    // NOTE: property name is not camel-case to maintain compatibility with render-python implementation
    @ApiModelProperty(value = "Effective size of pixels (in units of choice)")
    private final Double pixelsize;

    @ApiModelProperty(value = "Distance (in units of choice) from prior layer")
    private final Double distanceZ;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private LayoutData() {
        this(null, null, null, null, null, null, null, null, null, null);
    }

    public LayoutData(final String sectionId,
                      final String temca,
                      final String camera,
                      final Integer imageRow,
                      final Integer imageCol,
                      final Double stageX,
                      final Double stageY,
                      final Double rotation) {
        this(sectionId, temca, camera, imageRow, imageCol, stageX, stageY, rotation, null, null);
    }

    public LayoutData(final String sectionId,
                      final String temca,
                      final String camera,
                      final Integer imageRow,
                      final Integer imageCol,
                      final Double stageX,
                      final Double stageY,
                      final Double rotation,
                      final Double pixelsize,
                      final Double distanceZ) {
        this.sectionId = sectionId;
        this.temca = temca;
        this.camera = camera;
        this.imageRow = imageRow;
        this.imageCol = imageCol;
        this.stageX = stageX;
        this.stageY = stageY;
        this.rotation = rotation;
        this.pixelsize = pixelsize;
        this.distanceZ = distanceZ;
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

    public Double getPixelsize() {
        return pixelsize;
    }

    public Double getDistanceZ() {
        return distanceZ;
    }
}
