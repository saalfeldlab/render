package org.janelia.alignment.transform;

import java.util.Collections;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.junit.Assert;
import org.junit.Test;


/**
 * Tests the {@link ExponentialRecoveryOffsetTransform} class.
 */
public class SEMDistortionTransformATest {

    @Test
    public void testPersistence() {

        final SEMDistortionTransformA transform =
                new SEMDistortionTransformA(19.4, 64.8, 24.4, 972.0, 0);

        final String dataString = transform.toDataString();

        final SEMDistortionTransformA loadedTransform = new SEMDistortionTransformA();

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
    
}
