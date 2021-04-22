package org.janelia.render.client.spark;

import java.util.Date;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link N5Client} class.
 *
 * @author Eric Trautman
 */
public class N5ClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        final N5Client.Parameters p = new N5Client.Parameters();
        CommandLineParameters.parseHelp(p);

        p.downSamplingFactorsString = "2,2,2";
        final int[] downSamplingFactors = p.getDownSamplingFactors();
        Assert.assertNotNull("null downSamplingFactors returned", downSamplingFactors);
        Assert.assertEquals("wrong number of downSamplingFactors returned",
                            3, downSamplingFactors.length);
    }

    @Test
    public void testGetBoundsForRun() {
        final N5Client.Parameters p = new N5Client.Parameters();
        final StackVersion stackVersion = new StackVersion(new Date(),
                                                           null,
                                                           null,
                                                           null,
                                                           null,
                                                           null,
                                                           null,
                                                           null,
                                                           null);
        final StackMetaData stackMetaData = new StackMetaData(new StackId("o", "p", "s"),
                                                              stackVersion);
        final Bounds stackBounds = new Bounds(1.0,2.0,3.0,4.0,5.0,6.0);
        stackMetaData.setStats(new StackStats(stackBounds,
                                              1L, 1L, 1L, 1L,
                                              1, 1, 1, 1,
                                              null));
        final Bounds boundsForRun = p.getBoundsForRun(stackMetaData);
        Assert.assertEquals("null parameters should simply return stack bounds", stackBounds, boundsForRun);
    }

}
