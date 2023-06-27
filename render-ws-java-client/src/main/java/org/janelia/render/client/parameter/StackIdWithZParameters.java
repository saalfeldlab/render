package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.RenderDataClient;

/**
 * Parameters for identifying z-layers within a stack or group of stacks.
 *
 * @author Eric Trautman
 */
public class StackIdWithZParameters
        implements Serializable {

    @Parameter(
            names = "--stack",
            description = "Process these stacks")
    public List<String> stackNames;

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

    @Parameter(
            names = "--z",
            description = "Z values for layers to process (omit to process all z layers)")
    public List<Double> zValues;

    public List<StackId> getStackIdList(final RenderDataClient renderDataClient)
            throws IOException {
        final List<StackId> stackIdList;
        if (allStacksInAllProjects) {
            stackIdList = renderDataClient.getOwnerStacks();
        } else if (allStacksInProject) {
            stackIdList = renderDataClient.getProjectStacks();
        } else if (stackNames != null) {
            stackIdList = renderDataClient.getProjectStacks().stream()
                    .filter(stackId -> stackNames.contains(stackId.getStack()))
                    .collect(Collectors.toList());
        } else {
            throw new IOException("must specify either --stack, --allStacksInProject, or --allStacksInAllProjects");
        }
        return stackIdList;
    }

    /**
     * @return list of stack identifiers coupled with single z values ordered by stack and then z.
     */
    public List<StackWithZValues> getStackWithZList(final RenderDataClient renderDataClient)
            throws IOException {
        final List<StackWithZValues> stackWithZList = new ArrayList<>();
        final List<StackId> stackIdList = getStackIdList(renderDataClient);
        for (final StackId stackId : stackIdList) {
            final RenderDataClient projectClient = renderDataClient.buildClientForProject(stackId.getProject());
            for (final Double z : projectClient.getStackZValues(stackId.getStack(),
                                                                layerRange.minZ,
                                                                layerRange.maxZ,
                                                                zValues)) {
                stackWithZList.add(new StackWithZValues(stackId, z));
            }
        }
        return stackWithZList;
    }

}
