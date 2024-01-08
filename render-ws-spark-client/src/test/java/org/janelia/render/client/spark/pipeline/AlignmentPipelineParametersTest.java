package org.janelia.render.client.spark.pipeline;

import java.io.IOException;
import java.util.List;

import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link AlignmentPipelineParameters} class.
 *
 * @author Eric Trautman
 */
public class AlignmentPipelineParametersTest {

    @Test
    public void testAffineBlockSolverSetup()
            throws IOException {
        final AlignmentPipelineParameters pipelineParameters = loadTestParameters();

        Assert.assertNotNull("deserialized parameters are null", pipelineParameters);

        final AffineBlockSolverSetup affineBlockSolverSetup = pipelineParameters.getAffineBlockSolverSetup();
        Assert.assertNotNull("affineBlockSolverSetup is null", affineBlockSolverSetup);

        Assert.assertEquals("incorrect affineBlockSolverSetup.targetStack.stackSuffix value parsed",
                            "_align_pipe_alt_aa", affineBlockSolverSetup.targetStack.stackSuffix);

        Assert.assertTrue("incorrect affineBlockSolverSetup.targetStack.completeTargetStack value parsed",
                          affineBlockSolverSetup.targetStack.completeStack);
    }

    @Test
    public void testBuildStepClients()
            throws IOException {
        final AlignmentPipelineParameters pipelineParameters = loadTestParameters();

        final List<AlignmentPipelineStep> stepClients = pipelineParameters.buildStepClients();
        
        Assert.assertNotNull("stepClients is null", stepClients);
        Assert.assertEquals("incorrect number of stepClients", 7, stepClients.size());
    }

    private AlignmentPipelineParameters loadTestParameters() throws IOException {
        return AlignmentPipelineParameters.fromJsonFile("src/test/resources/pipeline/msem_alignment_pipeline.json");
    }
}
