package org.janelia.render.client.stack;

import org.janelia.render.client.CopyStackClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link Create16BitH5StackClient} class.
 *
 * @author Eric Trautman
 */
public class Create16BitH5StackClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new CopyStackClient.Parameters());
    }

    @Test
    public void testBuildRawSourceUrlForH5() {

        final Create16BitH5StackClient.Parameters p = new Create16BitH5StackClient.Parameters();
        p.rawRootDirectory = "/nrs/cellmap/data/jrc_mus-hippocampus-1/raw";
        p.skipFileValidation = true;

        final Create16BitH5StackClient client = new Create16BitH5StackClient(p);

        final String[][] testData = {
                {
                    "file:///nrs/cellmap/data/jrc_mus-hippocampus-1/align/Merlin-6049/2023/06/07/11/Merlin-6049_23-06-07_110544.uint8.h5?dataSet=/0-0-0/mipmap.0&z=0",
                    "file:///nrs/cellmap/data/jrc_mus-hippocampus-1/raw/Merlin-6049/2023/06/07/11/Merlin-6049_23-06-07_110544.raw-archive.h5?dataSet=/0-0-0/c0"
                }
        };

        for (final String[] testDatum : testData) {
            final String sourceUrl = testDatum[0];
            final String expected = testDatum[1];
            final String actual = client.buildRawSourceUrlForH5("testTile", sourceUrl);
            Assert.assertEquals("returned invalid raw URL", expected, actual);
        }
    }

    @Test
    public void testBuildRawSourceUrlForInLens() {

        final Create16BitH5StackClient.Parameters p = new Create16BitH5StackClient.Parameters();
        p.rawRootDirectory = "/nrs/cellmap/data/aic_desmosome-2/raw";
        p.skipFileValidation = true;

        final Create16BitH5StackClient client = new Create16BitH5StackClient(p);

        final String[][] testData = {
                {
                        "file:///nrs/cellmap/data/aic_desmosome-2/InLens/Gemini450-0113_21-01-27_030921_0-0-0-InLens.png",
                        "file:///nrs/cellmap/data/aic_desmosome-2/raw/Gemini450-0113/2021/01/27/03/Gemini450-0113_21-01-27_030921.raw-archive.h5?dataSet=/0-0-0/c0"
                }
        };

        for (final String[] testDatum : testData) {
            final String sourceUrl = testDatum[0];
            final String expected = testDatum[1];
            final String actual = client.buildRawSourceUrlForInLens("testTile", sourceUrl);
            Assert.assertEquals("returned invalid raw URL", expected, actual);
        }
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = new String[] {
                "--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
                "--owner", "fibsem",
                "--project", "jrc_liu_nih_ips_draq5_test_2",
                "--alignStack", "v2_acquire_align",
                "--rawStack", "v2_acquire_align_16bit",
                "--rawRootDirectory", "/nrs/fibsem/data/jrc_liu-nih_ips-draq5-test-2/raw",
                "--completeRawStack",
                "--z", "5000", "9740", "9741", "9742", "9743", "9744", "9745", "9746", "9747", "9748", "9749", "9750"
        };

        Create16BitH5StackClient.main(effectiveArgs);
    }

}
