package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackIdNamingGroup;
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
            names = "--projectPattern",
            description = "Process stacks with project names matching this pattern")
    public String projectPattern;

    @Parameter(
            names = "--stack",
            description = "Process stacks with these names",
            variableArity = true)
    public List<String> stackNames;

    @Parameter(
            names = "--stackPattern",
            description = "Process stacks with names matching this pattern")
    public String stackPattern;

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

    @JsonIgnore
    private StackIdNamingGroup namingGroup;

    public void setNamingGroup(final StackIdNamingGroup namingGroup) {
        this.namingGroup = namingGroup;
    }

    public boolean hasNoDefinedStacks() {
        return (stackNames == null) || stackNames.isEmpty();
    }

    public List<StackId> getStackIdList(final RenderDataClient renderDataClient)
            throws IOException {

        final List<StackId> eligibleStackIds = getEligibleStackIds(renderDataClient);
        final Pattern cpp = compilePattern(projectPattern,
                                           (namingGroup == null) ? null : namingGroup.getProjectPattern());
        final Pattern csp = compilePattern(stackPattern,
                                           (namingGroup == null) ? null : namingGroup.getStackPattern());

        return eligibleStackIds.stream()
                .filter(stackId -> {
                    return ((cpp == null) || cpp.matcher(stackId.getProject()).matches()) &&    // project matches and
                           (((csp != null) && csp.matcher(stackId.getStack()).matches()) ||     // ( stack matches or
                            ((stackNames != null) && stackNames.contains(stackId.getStack()))); //   stack is in list )
                })
                .collect(Collectors.toList());
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

    private List<StackId> getEligibleStackIds(final RenderDataClient renderDataClient)
            throws IOException {

        final boolean hasProjectPattern = ((projectPattern != null) && ! projectPattern.isEmpty()) ||
                                          ((namingGroup != null) && namingGroup.hasProjectPattern());
        final boolean hasStackPattern = ((stackPattern != null) && ! stackPattern.isEmpty()) ||
                                        ((namingGroup != null) && namingGroup.hasStackPattern());
        final boolean hasStackNames = (stackNames != null) && ! stackNames.isEmpty();

        if (! (hasProjectPattern || hasStackPattern || hasStackNames)) {
            throw new IOException("must specify at least one of --projectPattern, --stack, or --stackPattern");
        }

        // if projectPattern is specified, fetch all stacks for the client's owner
        // otherwise only fetch stacks for the client's project
        return hasProjectPattern ? renderDataClient.getOwnerStacks() : renderDataClient.getProjectStacks();
    }

    private Pattern compilePattern(final String defaultPattern,
                                   final String namingGroupPattern) {
        if ((namingGroupPattern != null) && (! namingGroupPattern.isEmpty())) {
            return Pattern.compile(namingGroupPattern);
        } else if ((defaultPattern != null) && (! defaultPattern.isEmpty())) {
            return Pattern.compile(defaultPattern);
        } else {
            return null;
        }
    }

}
