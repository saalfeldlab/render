package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import org.janelia.render.client.RenderDataClient;

/**
 * Parameters for deriving warp transformations.
 *
 * @author Eric Trautman
 */
public class WarpStackParameters {

    @Parameter(
            names = "--stack",
            description = "Montage stack name",
            required = true)
    public String montageStack;

    @Parameter(
            names = "--alignOwner",
            description = "Name of align stack owner (default is same as montage stack owner)",
            required = false)
    public String alignOwner;

    @Parameter(
            names = "--alignProject",
            description = "Name of align stack project (default is same as montage stack project)",
            required = false)
    public String alignProject;

    @Parameter(
            names = "--alignStack",
            description = "Align stack name",
            required = true)
    public String alignStack;

    @Parameter(
            names = "--targetOwner",
            description = "Name of target stack owner (default is same as montage stack owner)",
            required = false)
    public String targetOwner;

    @Parameter(
            names = "--targetProject",
            description = "Name of target stack project (default is same as montage stack project)",
            required = false)
    public String targetProject;

    @Parameter(
            names = "--targetStack",
            description = "Target stack name",
            required = true)
    public String targetStack;

    @JsonIgnore
    private String baseDataUrl;

    public void initDefaultValues(final RenderWebServiceParameters renderWeb) {

        this.baseDataUrl = renderWeb.baseDataUrl;

        if (this.alignOwner == null) {
            this.alignOwner = renderWeb.owner;
        }
        if (this.alignProject == null) {
            this.alignProject = renderWeb.project;
        }

        if (this.targetOwner == null) {
            this.targetOwner = renderWeb.owner;
        }
        if (this.targetProject == null) {
            this.targetProject = renderWeb.project;
        }
    }

    @JsonIgnore
    public RenderDataClient getAlignDataClient() throws IllegalStateException {
        verifyState();
        return new RenderDataClient(baseDataUrl, alignOwner, alignProject);
    }

    @JsonIgnore
    public RenderDataClient getTargetDataClient() throws IllegalStateException {
        verifyState();
        return new RenderDataClient(baseDataUrl, targetOwner, targetProject);
    }

    private void verifyState() {
        if (baseDataUrl == null) {
            throw new IllegalStateException(
                    "code error: default values not initialized, need to add call to initDefaultValues");
        }
    }
}
