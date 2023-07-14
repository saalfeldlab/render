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
            description = "Process these stacks",
            variableArity = true)
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
            description = "Z values for layers to process (omit to process all z layers)",
            variableArity = true)
    public List<Double> zValues;

    @Parameter(
            names = "--zValuesPerBatch",
            description = "Number of stack z values to batch together when distributing work")
    public int zValuesPerBatch = 1;

    public boolean hasNoDefinedStacks() {
        return (stackNames == null) || (stackNames.size() == 0);
    }

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
     * @return list of stack identifiers coupled with --zValuesPerBatch z values
     *         that is ordered by stack and then z.
     */
    public List<StackWithZValues> getStackWithZList(final RenderDataClient renderDataClient)
            throws IOException {
        if (zValuesPerBatch < 1) {
            throw new IllegalArgumentException("--zValuesPerBatch must be greater than zero");
        }
        final List<StackWithZValues> stackWithZList = new ArrayList<>();
        final List<StackId> stackIdList = getStackIdList(renderDataClient);
        for (final StackId stackId : stackIdList) {
            final RenderDataClient projectClient = renderDataClient.buildClientForProject(stackId.getProject());
            final List<Double> stackZValues = projectClient.getStackZValues(stackId.getStack(),
                                                                            layerRange.minZ,
                                                                            layerRange.maxZ,
                                                                            zValues);
            if (zValuesPerBatch >= stackZValues.size()) {
                stackWithZList.add(new StackWithZValues(stackId, stackZValues));
            } else {
                for (int fromIndex = 0; fromIndex < stackZValues.size(); fromIndex += zValuesPerBatch) {
                    final int toIndex = Math.min(stackZValues.size(), fromIndex + zValuesPerBatch);
                    final List<Double> batchedZValues = new ArrayList<>(stackZValues.subList(fromIndex, toIndex));
                    stackWithZList.add(new StackWithZValues(stackId, batchedZValues));
                }
            }
        }
        return stackWithZList;
    }

}
