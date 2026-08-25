package org.janelia.render.client;

import org.janelia.alignment.spec.validator.TemTileSpecValidator;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests the {@link TileSpecValidatorParameters} class.
 *
 * @author Eric Trautman
 */
public class RenderWebServiceParametersWithValidatorTest {

    @Test
    public void testGetValidator()
            throws Exception {

        final TileSpecValidatorParameters p = new TileSpecValidatorParameters();
        p.validatorClass = "org.janelia.alignment.spec.validator.TemTileSpecValidator";
        p.validatorData = "minCoordinate:0,maxCoordinate:100000,minSize:100,maxSize:20000";

        final TileSpecValidator tileSpecValidator = p.getValidatorInstance();

        if (tileSpecValidator instanceof final TemTileSpecValidator temTileSpecValidator) {

            final double delta = 0.001;

            assertEquals(0, temTileSpecValidator.getMinCoordinate(), delta, "invalid min coordinate parsed");
            assertEquals(100000, temTileSpecValidator.getMaxCoordinate(), delta,
                         "invalid max coordinate parsed");
            assertEquals(100, temTileSpecValidator.getMinSize(), delta, "invalid min coordinate parsed");
            assertEquals(20000, temTileSpecValidator.getMaxSize(), delta, "invalid min coordinate parsed");

        } else {
            fail("wrong instance created: " + tileSpecValidator.getClass());
        }
    }


}
