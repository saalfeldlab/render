package org.janelia.alignment.spec;

/**
 * Tile information from the layout file.
 *
 * @author Eric Trautman
 */
public class LayoutData {

    private String temca;
    private String camera;
    private String imageRow;
    private String imageCol;

    public LayoutData(String temca,
                      String camera,
                      String imageRow,
                      String imageCol) {
        this.temca = temca;
        this.camera = camera;
        this.imageRow = imageRow;
        this.imageCol = imageCol;
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
}
