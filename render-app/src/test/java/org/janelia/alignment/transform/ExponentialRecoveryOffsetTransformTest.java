package org.janelia.alignment.transform;

import java.util.Collections;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(dataString, loadedTransform.toDataString(), "data strings do not match");

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
        final double[][] expectedResults = { {0 + 14.9, 1111}, {200 + 8.8, 1111}, {600 + 5.9, 1111} };

        for (int i = 0; i < testLocations.length; i++) {
            final double[] result = transform.apply(testLocations[i]);
            assertEquals(expectedResults[i][0], result[0], 0.1,
                         "bad x result for test " + i);
            assertEquals(expectedResults[i][1], result[1], 0.0001,
                         "bad y result for test " + i);
        }
    }

    @Test
    public void testDerivedTileBoundsAfterTransform() {
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId("21-09-24_002136_0-0-0.8783.0");
        tileSpec.setWidth(7687.0);
        tileSpec.setHeight(3500.0);
        tileSpec.addTransformSpecs(Collections.singletonList(
                new LeafTransformSpec(ExponentialRecoveryOffsetTransform.class.getName(),
                                      "50.263852018175896,0.0012483526717175487,-49.51720446569045,1")));

        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
        final TileBounds bounds = tileSpec.toTileBounds();

        assertEquals(tileSpec.getWidth(), bounds.getWidth(), 0.1,
                     "invalid bounds width after transform");

        final double expectedHeight = tileSpec.getHeight() - 49;
        assertEquals(expectedHeight, bounds.getHeight(), 0.1,
                     "invalid bounds height after transform");
    }

}
