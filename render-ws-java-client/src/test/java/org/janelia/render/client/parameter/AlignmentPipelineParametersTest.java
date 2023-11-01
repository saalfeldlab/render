package org.janelia.render.client.parameter;

import java.io.StringReader;

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
    public void testJson() {
        final AlignmentPipelineParameters pipelineParameters =
                AlignmentPipelineParameters.fromJson(new StringReader(PIPELINE_JSON));

        Assert.assertNotNull("deserialized parameters are null", pipelineParameters);

        final AffineBlockSolverSetup affineBlockSolverSetup = pipelineParameters.getAffineBlockSolverSetup();
        Assert.assertNotNull("affineBlockSolverSetup is null", affineBlockSolverSetup);

        Assert.assertEquals("incorrect affineBlockSolverSetup.targetStack.stackSuffix value parsed",
                            "_align_pipe_aa", affineBlockSolverSetup.targetStack.stackSuffix);

        Assert.assertTrue("incorrect affineBlockSolverSetup.targetStack.completeTargetStack value parsed",
                          affineBlockSolverSetup.targetStack.completeStack);
    }

    private static final String PIPELINE_JSON =
            "{\n" +
            "  \"multiProject\": {\n" +
            "    \"baseDataUrl\" : \"http://10.40.3.113:8080/render-ws/v1\",\n" +
            "    \"owner\" : \"hess_wafer_53\",\n" +
            "    \"project\" : \"cut_000_to_009\",\n" +
            "    \"stackIdWithZ\" : {\n" +
            "      \"allStacksInProject\": false,\n" +
            "      \"allStacksInAllProjects\": false,\n" +
            "      \"stackNames\": [\n" +
            "        \"c000_s095_v01\"\n" +
            "      ]\n" +
            "    }\n" +
            "  },\n" +
            "  \"affineBlockSolverSetup\": {\n" +
            "    \"distributedSolve\": {\n" +
            "      \"threadsWorker\": 2,\n" +
            "      \"threadsGlobal\": 32\n" +
            "    },\n" +
            "    \"targetStack\": {\n" +
            "      \"stackSuffix\": \"_align_pipe_aa\",\n" +
            "      \"completeStack\": true\n" +
            "    },\n" +
            "    \"matches\": {\n" +
            "      \"matchCollection\": \"c000_s095_v01_match_agg2\"\n" +
            "    },\n" +
            "    \"blockPartition\": {\n" +
            "      \"sizeX\": 12000,\n" +
            "      \"sizeY\": 12000\n" +
            "    },\n" +
            "    \"blockOptimizer\": {\n" +
            "      \"lambdasRigid\": [ 1.0, 1.0, 0.9, 0.3, 0.01 ],\n" +
            "      \"lambdasTranslation\": [ 1.0, 0.0, 0.0, 0.0, 0.0 ],\n" +
            "      \"lambdasRegularization\": [ 0.0, 0.0, 0.0, 0.0, 0.0 ],\n" +
            "      \"iterations\": [ 50, 50, 30, 25, 25 ],\n" +
            "      \"maxPlateauWidth\": [ 25, 25, 15, 10, 10 ]\n" +
            "    },\n" +
            "    \"maxNumMatches\": 0\n" +
            "  }\n" +
            "}";
}
