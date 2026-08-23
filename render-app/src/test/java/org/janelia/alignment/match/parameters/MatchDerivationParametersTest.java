package org.janelia.alignment.match.parameters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the {@link MatchDerivationParameters} class.
 *
 * @author Eric Trautman
 */
public class MatchDerivationParametersTest {

    @Test
    public void getGetMatchMaxEpsilonForRenderScale() {
        final MatchDerivationParameters parameters = new MatchDerivationParameters();
        final Float maxEpsilon = parameters.getMatchMaxEpsilonForRenderScale(1.0);
        assertNull(maxEpsilon,
                   "maxEpsilon should be null when neither matchMaxEpsilon nor matchMaxEpsilonFullScale are specified");
    }

    @Test
    public void testValidateAndSetDefaults() {
        final MatchDerivationParameters parameters = new MatchDerivationParameters();
        final IllegalArgumentException expectedException =
                assertThrows(IllegalArgumentException.class,
                                    () -> parameters.validateAndSetDefaults("test"));
        assertEquals("test matchMaxEpsilonFullScale must be defined", expectedException.getMessage(),
                     "invalid exception message");
    }

}