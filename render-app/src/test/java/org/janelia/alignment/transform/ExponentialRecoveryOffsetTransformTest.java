package org.janelia.alignment.transform;

import java.util.Collections;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.junit.Assert;
import org.junit.Test;


/**
 * Tests the {@link ExponentialRecoveryOffsetTransform} class.
 */
public class ExponentialRecoveryOffsetTransformTest {

    @Test
    public void testPersistence() {

        final ExponentialRecoveryOffsetTransform transform =
                new ExponentialRecoveryOffsetTransform(9.47533, 0.0053344, -14.99262, 0);

        final String dataString = transform.toDataString();

        final ExponentialRecoveryOffsetTransform loadedTransform = new ExponentialRecoveryOffsetTransform();

        loadedTransform.init(dataString);

        Assert.assertEquals("data strings do not match", dataString, loadedTransform.toDataString());

        final TileSpec tileSpec = new TileSpec();
        tileSpec.setWidth(100.0);
        tileSpec.setHeight(100.0);
        final LeafTransformSpec transformSpec = new LeafTransformSpec(loadedTransform.getClass().getName(),
                                                                      dataString);
        tileSpec.addTransformSpecs(Collections.singletonList(transformSpec));
        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

        System.out.println(tileSpec.toJson());
    }

    @Test
    public void testApply() {

        final ExponentialRecoveryOffsetTransform transform =
                new ExponentialRecoveryOffsetTransform(9.47533, 0.0053344, -14.99262, 0);

        final double[][] testLocations = {   {0,        1111}, {200,       1111}, {600,       1111} };
        final double[][] expectedResults = { {0 - 14.9, 1111}, {200 - 8.8, 1111}, {600 - 5.9, 1111} };

        for (int i = 0; i < testLocations.length; i++) {
            final double[] result = transform.apply(testLocations[i]);
            Assert.assertEquals("bad x result for test " + i,
                                expectedResults[i][0], result[0], 0.1);
            Assert.assertEquals("bad y result for test " + i,
                                expectedResults[i][1], result[1], 0.0001);
        }
    }

}
