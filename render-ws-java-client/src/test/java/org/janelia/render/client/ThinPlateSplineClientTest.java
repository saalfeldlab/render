package org.janelia.render.client;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.CoordinateTransform;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link ThinPlateSplineClient} class.
 *
 * @author Eric Trautman
 */
public class ThinPlateSplineClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ThinPlateSplineClient.Parameters());
    }

    @Test
    public void testBuildTransformSpec() throws Exception {

        final ThinPlateSplineClient.Parameters parameters = new ThinPlateSplineClient.Parameters();

        final double[] sourceXs = { 0.0, 22.0 };
        final double[] sourceYs = { 0.0, 22.0 };
        final double[] targetXs = { 10.0, 32.0 };
        final double[] targetYs = { 10.0, 32.0 };

        final List<Double> landmarkValues = new ArrayList<>();
        for (int i = 0; i < sourceXs.length; i++) {
            landmarkValues.add(sourceXs[i]);
            landmarkValues.add(sourceYs[i]);
        }

        for (int i = 0; i < targetXs.length; i++) {
            landmarkValues.add(targetXs[i]);
            landmarkValues.add(targetYs[i]);
        }

        parameters.numberOfLandmarks = sourceXs.length;
        parameters.landmarkValues = landmarkValues;

        final ThinPlateSplineClient client = new ThinPlateSplineClient(parameters);
        final LeafTransformSpec transformSpec = client.buildTransformSpec();

        final CoordinateTransform transform = transformSpec.getNewInstance();

        final double testDelta = 0.0001;
        for (int i = 0; i < sourceXs.length; i++) {
            final double[] testResult = transform.apply(new double[] { sourceXs[i], sourceYs[i] });
            Assert.assertEquals("invalid x for landmark " + i, targetXs[i], testResult[0], testDelta);
            Assert.assertEquals("invalid y for landmark " + i, targetYs[i], testResult[1], testDelta);
        }

//        ThinPlateSplineClient.main(
//                new String[] { "--numberOfLandmarks", "1",
//                               "--outputFile", "/tmp/foo.json",
//                               "0.0", "0.0",
//                               "10.0", "10.0"
//                });
    }

}
