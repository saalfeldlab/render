package org.janelia.render.client;

import org.janelia.alignment.spec.validator.TemTileSpecValidator;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link RenderDataClientParametersWithValidator} class.
 *
 * @author Eric Trautman
 */
public class RenderDataClientParametersWithValidatorTest {

    @Test
    public void testGetValidator()
            throws Exception {

        final RenderDataClientParametersWithValidator p = new RenderDataClientParametersWithValidator();
        p.validatorClass = "org.janelia.alignment.spec.validator.TemTileSpecValidator";
        p.validatorData = "minCoordinate:0,maxCoordinate:100000,minSize:100,maxSize:20000";

        final TileSpecValidator tileSpecValidator = p.getValidatorInstance();

        if (tileSpecValidator instanceof TemTileSpecValidator) {

            final TemTileSpecValidator temTileSpecValidator = (TemTileSpecValidator) tileSpecValidator;

            final double delta = 0.001;

            Assert.assertEquals("invalid min coordinate parsed", 0, temTileSpecValidator.getMinCoordinate(), delta);
            Assert.assertEquals("invalid max coordinate parsed", 100000, temTileSpecValidator.getMaxCoordinate(), delta);
            Assert.assertEquals("invalid min coordinate parsed", 100, temTileSpecValidator.getMinSize(), delta);
            Assert.assertEquals("invalid min coordinate parsed", 20000, temTileSpecValidator.getMaxSize(), delta);

        } else {
            Assert.fail("wrong instance created: " + tileSpecValidator.getClass());
        }
    }


}
