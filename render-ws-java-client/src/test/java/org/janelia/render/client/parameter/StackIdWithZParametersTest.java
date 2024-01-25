package org.janelia.render.client.parameter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackIdNamingGroup;
import org.janelia.render.client.RenderDataClient;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link StackIdWithZParameters} class.
 *
 * @author Eric Trautman
 */
public class StackIdWithZParametersTest {

    private final String owner = "testOwner";
    private final String projectA = "testProjectA";
    private final String projectB = "testProjectB";

    private final List<StackId> stackIds = Arrays.asList(
            new StackId(owner, projectA, "stack1"),
            new StackId(owner, projectA, "stack2"),
            new StackId(owner, projectA, "stack1_align"),
            new StackId(owner, projectA, "stack2_align"),
            new StackId(owner, projectA, "stack1_align_ic"),
            new StackId(owner, projectA, "stack2_align_ic"),
            new StackId(owner, projectB, "stack1"),
            new StackId(owner, projectB, "stack2"),
            new StackId(owner, projectB, "stack1_align"),
            new StackId(owner, projectB, "stack2_align"),
            new StackId(owner, projectB, "stack1_align_ic"),
            new StackId(owner, projectB, "stack2_align_ic")
    );

    private final RenderDataClient mockDataClient = new MockRenderDataClient(owner, projectA, stackIds);

    @Test
    public void testGetEligibleStackIds() throws IOException {

        final StackIdWithZParameters params = new StackIdWithZParameters();

        params.projectPattern = ".*ProjectA$";
        List<StackId> eligibleStackIds = params.getEligibleStackIds(mockDataClient);
        Assert.assertEquals("all stacks should be returned with default project pattern",
                            12, eligibleStackIds.size());

        params.projectPattern = null;
        params.stackPattern = "stack1.*";
        eligibleStackIds = params.getEligibleStackIds(mockDataClient);
        Assert.assertEquals("only default project stacks should be returned with default stack pattern",
                            6, eligibleStackIds.size());

        params.stackPattern = null;
        params.setNamingGroup(new StackIdNamingGroup(".*ProjectB$", null));
        eligibleStackIds = params.getEligibleStackIds(mockDataClient);
        Assert.assertEquals("all stacks should be returned with naming group project pattern",
                            12, eligibleStackIds.size());

        params.setNamingGroup(new StackIdNamingGroup(null, "stack2.*"));
        eligibleStackIds = params.getEligibleStackIds(mockDataClient);
        Assert.assertEquals("only default project stacks should be returned with naming group stack pattern",
                            6, eligibleStackIds.size());
    }

    @Test
    public void testGetStackIdList() throws IOException {

        final StackIdWithZParameters params = new StackIdWithZParameters();

        params.projectPattern = ".*ProjectA$";
        List<StackId> stackIdList = params.getStackIdList(mockDataClient);
        Assert.assertEquals("incorrect number of stacks returned for default project pattern",
                            6, stackIdList.size());

        params.projectPattern = null;
        params.stackPattern = "stack1.*";
        stackIdList = params.getStackIdList(mockDataClient);
        Assert.assertEquals("incorrect number of stacks returned default stack pattern",
                            3, stackIdList.size());

        params.projectPattern = ".*ProjectA$";
        params.stackPattern = null;
        params.setNamingGroup(new StackIdNamingGroup(".*ProjectB$", null));
        stackIdList = params.getStackIdList(mockDataClient);
        Assert.assertEquals("incorrect number of stacks returned for naming group project pattern",
                            6, stackIdList.size());

        final int projectBStackCount = (int) stackIdList.stream()
                .filter(stackId -> stackId.getProject().equals(projectB)).count();
        Assert.assertEquals("all stacks should be from project B (naming group should override default)",
                            6, projectBStackCount);
    }

    private static class MockRenderDataClient extends RenderDataClient {

        final List<StackId> ownerStackIds;

        public MockRenderDataClient(final String owner,
                                    final String project,
                                    final List<StackId> stackIds) {
            super("notApplicable", owner, project);
            this.ownerStackIds = stackIds.stream()
                    .filter(stackId -> stackId.getOwner().equals(owner)).collect(Collectors.toList());
        }

        @Override
        public List<StackId> getOwnerStacks() {
            return ownerStackIds;
        }

        @Override
        public List<StackId> getProjectStacks() {
            return getStackIds(getProject());
        }

        @Override
        public List<StackId> getStackIds(final String project) {
            return ownerStackIds.stream()
                    .filter(stackId -> stackId.getProject().equals(project)).collect(Collectors.toList());
        }
    }
}
