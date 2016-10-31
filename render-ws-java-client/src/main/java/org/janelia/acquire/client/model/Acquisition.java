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
    private String MosaicType;
    private String StackName;
    private Double stackResolutionX = 4.0;
    private Double stackResolutionY = 4.0;
    private Double stackResolutionZ = 35.0;

    public Acquisition() {
    }

    public Acquisition(Long acqUID, String projectName, String projectOwner, String mosaicType, String stackName) {
        AcqUID = acqUID;
        ProjectName = projectName;
        ProjectOwner = projectOwner;
        MosaicType = mosaicType;
        StackName = stackName;
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

    public String getMosaicType() { return MosaicType; }

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
