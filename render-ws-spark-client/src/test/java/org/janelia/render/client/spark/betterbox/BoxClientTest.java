package org.janelia.render.client.spark.betterbox;

import org.apache.spark.SparkConf;
import org.janelia.alignment.util.ProcessTimer;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link BoxClient} class.
 *
 * This test assumes access to a running render web service instance, so it is ignored by default.
 * To run the test, comment out the Ignore annotation and update the client arguments as needed.
 *
 * Use the --explainPlan option to see how boxes get partitioned for rendering.
 *
 * You can also render small layers locally by limiting the z range and
 * using the --label option which removes the need to access actual source tile data.
 *
 * @author Eric Trautman
 */
@Ignore
public class BoxClientTest {

    @Test
    public void testPlan() throws Exception {

        final int numberOfConcurrentTasks = 8;

        // NOTE: even with the --explainPlan option, partition data gets written here
        final String rootDirectory = "/Users/trautmane/Desktop/box/rendered_boxes";

        // small rough tiles stack
        final String[] args = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "trautmane_test", "--stack", "rough_tiles",
                "--width", "1024", "--height", "1024",
                "--maxLevel", "7",
                "--z", "3",
                "--rootDirectory", rootDirectory,
//                "--label",
//                "--maxOverviewWidthAndHeight", "192",
//                "--format", "png"
                "--explainPlan"
        };

        // large FAFB stack
//        final String[] args = {
//                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
//                "--owner", "flyTEM",
//                "--project", "FAFB00", "--stack", "v14_align_tps_20170818",
//                "--width", "1024", "--height", "1024",
////                "--maxLevel", "0",
//                "--maxLevel", "9",
//                "--z", "3333",
//                "--rootDirectory", rootDirectory,
//                "--createIGrid",
//                "--explainPlan"
//        };

        final ProcessTimer processTimer = new ProcessTimer();

        final BoxClient.Parameters parameters = new BoxClient.Parameters();
        parameters.parse(args);

        LOG.info("testPlan: entry, parameters={}", parameters);

        final BoxClient boxClient = new BoxClient(parameters);

        final String master = "local[" + numberOfConcurrentTasks + "]";

        final SparkConf sparkConf = new SparkConf().setMaster(master).setAppName(this.getClass().getSimpleName());
        boxClient.run(sparkConf);

        LOG.info("testPlan: exit, processing completed in {}", processTimer);
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxClientTest.class);

}
