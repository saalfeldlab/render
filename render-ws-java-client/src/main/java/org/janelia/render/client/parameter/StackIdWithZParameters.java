package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.RenderDataClient;

/**
 * Parameters for identifying a stack or group of stacks.
 *
 * @author Eric Trautman
 */
public class StackIdWithZParameters
        implements Serializable {

    @Parameter(
            names = "--stack",
            description = "Process this stack")
    public String stack;

    @Parameter(
            names = "--allStacksInProject",
            description = "Process all stacks in --project",
            arity = 0)
    public boolean allStacksInProject = false;

    @Parameter(
            names = "--allStacksInAllProjects",
            description = "Process all stacks in all projects for --owner",
            arity = 0)
    public boolean allStacksInAllProjects = false;

    @ParametersDelegate
    public ZRangeParameters layerRange = new ZRangeParameters();

    @Parameter(description = "Z values for layers to process (omit to process all z layers in stack)")
    public List<Double> zValues;

    public List<StackId> getStackIdList(final RenderDataClient renderDataClient)
            throws IOException {
        final List<StackId> stackIdList;
        if (allStacksInAllProjects) {
            stackIdList = renderDataClient.getOwnerStacks();
        } else if (allStacksInProject) {
            stackIdList = renderDataClient.getProjectStacks();
        } else if (stack != null) {
            final RenderWebServiceUrls urls = renderDataClient.getUrls();
            stackIdList = Collections.singletonList(new StackId(urls.getOwner(), renderDataClient.getProject(), stack));
        } else {
            throw new IOException("must specify either --stack, --allStacksInProject, or --allStacksInAllProjects");
        }
        return stackIdList;
    }

    public List<StackIdWithZ> getStackIdWithZList(final RenderDataClient renderDataClient)
            throws IOException {
        final List<StackIdWithZ> stackIdWithZList = new ArrayList<>();
        final List<StackId> stackIdList = getStackIdList(renderDataClient);
        for (final StackId stackId : stackIdList) {
            final RenderDataClient projectClient = renderDataClient.buildClientForProject(stackId.getProject());
            for (final Double z : projectClient.getStackZValues(stackId.getStack(),
                                                                layerRange.minZ,
                                                                layerRange.maxZ,
                                                                zValues)) {
                stackIdWithZList.add(new StackIdWithZ(stackId, z));
            }
        }
        return stackIdWithZList;
    }

    public static class StackIdWithZ implements Serializable {
        public final StackId stackId;
        public final Double z;

        public StackIdWithZ(final StackId stackId,
                            final Double z) {
            this.stackId = stackId;
            this.z = z;
        }
    }

}
