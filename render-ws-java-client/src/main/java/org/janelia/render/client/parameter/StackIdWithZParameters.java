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
            variableArity = true,
            required = true)
    public List<String> stackNames;

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
        return (stackNames == null) || stackNames.isEmpty();
    }

    public List<StackId> getStackIdList(final RenderDataClient renderDataClient)
            throws IOException {
        final List<StackId> stackIdList;
        if (stackNames != null) {
            stackIdList = renderDataClient.getProjectStacks().stream()
                    .filter(stackId -> stackNames.contains(stackId.getStack()))
                    .collect(Collectors.toList());
        } else {
            throw new IOException("must specify at least one --stack");
        }
        return stackIdList;
    }

    /**
     * @return list of stack identifiers coupled with --zValuesPerBatch z values
     *         that is ordered by stack and then z.
     */
    public List<StackWithZValues> buildListOfStackWithBatchedZ(final RenderDataClient renderDataClient)
            throws IOException, IllegalArgumentException {
        return buildListOfStackWithBatchedZ(renderDataClient, this.zValuesPerBatch);
    }

    /**
     * @return list of stack identifiers coupled with all z values for the stack
     *         that is ordered by stack.
     */
    public List<StackWithZValues> buildListOfStackWithAllZ(final RenderDataClient renderDataClient)
            throws IOException, IllegalArgumentException {
        return buildListOfStackWithBatchedZ(renderDataClient, Integer.MAX_VALUE);
    }

    /**
     * @return list of stack identifiers coupled with explicitZValuesPerBatch z values
     *         that is ordered by stack and then z.
     */
    public List<StackWithZValues> buildListOfStackWithBatchedZ(final RenderDataClient renderDataClient,
                                                               final int explicitZValuesPerBatch)
            throws IOException, IllegalArgumentException {
        if (explicitZValuesPerBatch < 1) {
            throw new IllegalArgumentException("zValuesPerBatch must be greater than zero");
        }
        final List<StackWithZValues> batchedList = new ArrayList<>();
        final List<StackId> stackIdList = getStackIdList(renderDataClient);
        for (final StackId stackId : stackIdList) {
            final RenderDataClient projectClient = renderDataClient.buildClientForProject(stackId.getProject());
            final List<Double> stackZValues = projectClient.getStackZValues(stackId.getStack(),
                                                                            layerRange.minZ,
                                                                            layerRange.maxZ,
                                                                            zValues);
            if (explicitZValuesPerBatch >= stackZValues.size()) {
                batchedList.add(new StackWithZValues(stackId, stackZValues));
            } else {
                for (int fromIndex = 0; fromIndex < stackZValues.size(); fromIndex += explicitZValuesPerBatch) {
                    final int toIndex = Math.min(stackZValues.size(), fromIndex + explicitZValuesPerBatch);
                    final List<Double> batchedZValues = new ArrayList<>(stackZValues.subList(fromIndex, toIndex));
                    batchedList.add(new StackWithZValues(stackId, batchedZValues));
                }
            }
        }

        if (batchedList.isEmpty()) {
            throw new IllegalArgumentException("no stack z-layers match parameters");
        }

        return batchedList;
    }

}
