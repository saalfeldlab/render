package org.janelia.render.client.spark;

import org.apache.spark.SparkConf;
import org.janelia.alignment.util.ProcessTimer;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.Assert;

/**
 * Tests the {@link org.janelia.render.client.spark.HierarchicalAlignmentClient} class.
 *
 * @author Eric Trautman
 */
public class HierarchicalAlignmentClientTest {

    @Test
    public void testGetPotentialPairsPerStack() throws Exception {

        final int[][] data = {
                // numberOfLayers, distance, expectedResult
                {               3,        1,              2 }, // ab, bc
                {               5,        1,              4 }, // ab, bc, cd, de
                {              10,        2,             17 },
                {              10,        3,             24 },
                {               4,       20,              6 }, // ab, ac, ad, bc, bd, cd
                {               5,        8,             10 }  // ab, ac, ad, ae, bc, bd, be, cd, ce, de
        };

        for (final int[] testData : data) {
            final int numberOfLayers = testData[0];
            final int distance = testData[1];
            final long expectedResult = testData[2];

            final long result = HierarchicalAlignmentClient.getPotentialPairsPerStack(numberOfLayers, distance);

            Assert.assertEquals("invalid result for " + numberOfLayers + " layers and distance " + distance,
                                expectedResult, result);
        }
    }

    /**
     * This test assumes access to a running render web service instance, so it is commented out.
     * To run the test, uncomment the Test annotation and update the client arguments as needed.
     */
    //@Test
    @SuppressWarnings("unused")
    public void testWarp() throws Exception {

        final int numberOfConcurrentTasks = 1;

        final String[] args = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "trautmane_test", "--stack", "rough_tiles",
                "--firstTier", "2", "--lastTier", "2",
                "--boxBaseDataUrl", "http://renderer.int.janelia.org:8080/render-ws/v1",
                "--SIFTfdSize", "8", "--SIFTminScale", "1.0", "--SIFTmaxScale", "1.0", "--SIFTsteps", "3",
                "--matchRod", "0.8", "--matchMaxEpsilon", "20.0", "--matchMinInlierRatio", "0.0", "--matchMinNumInliers", "8",
                "--maxFeatureCacheGb", "1",
                "--solverParametersTemplate", "/home/trautmane/render/warp_stack/02_rough/try2/template_solve_affine.json",
                "--keepExisting", "ALIGN"
        };

        final ProcessTimer processTimer = new ProcessTimer();

        final HierarchicalAlignmentClient.Parameters parameters = new HierarchicalAlignmentClient.Parameters();
        parameters.parse(args);

        LOG.info("testWarp: entry, parameters={}", parameters);

        final String master = "local[" + numberOfConcurrentTasks + "]";
        final SparkConf sparkConf = new SparkConf().setMaster(master).setAppName(this.getClass().getSimpleName());

        final HierarchicalAlignmentClient client = new HierarchicalAlignmentClient(parameters, sparkConf);

        client.run();

        LOG.info("testWarp: exit, processing completed in {}", processTimer);
    }

    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalAlignmentClientTest.class);

}
