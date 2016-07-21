package org.janelia.acquire.client.model;

/**
 * Image Catcher data for one acquisition.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("ALL")
public class Acquisition {

    private Long AcqUID;
    private String ProjectName;
    private String ProjectOwner;
    private String StackName;
    private Double stackResolutionX = 4.0;
    private Double stackResolutionY = 4.0;
    private Double stackResolutionZ = 35.0;

    public Acquisition() {
    }

    public Long getAcqUID() {
        return AcqUID;
    }

    public String getProjectName() {
        return ProjectName;
    }

    public String getProjectOwner() {
        return ProjectOwner;
    }

    public String getStackName() {
        return StackName;
    }

    public Double getStackResolutionX() {
        return stackResolutionX;
    }

    public Double getStackResolutionY() {
        return stackResolutionY;
    }

    public Double getStackResolutionZ() {
        return stackResolutionZ;
    }
}
