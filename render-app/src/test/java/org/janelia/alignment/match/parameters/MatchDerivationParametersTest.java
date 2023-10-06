package org.janelia.alignment.match.parameters;

import org.junit.Assert;
import org.junit.Test;

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
        Assert.assertNull("maxEpsilon should be null when neither matchMaxEpsilon nor matchMaxEpsilonFullScale are specified",
                          maxEpsilon);
    }

    @Test
    public void testValidateAndSetDefaults() {
        final MatchDerivationParameters parameters = new MatchDerivationParameters();
        final IllegalArgumentException expectedException =
                Assert.assertThrows(IllegalArgumentException.class,
                                    () -> parameters.validateAndSetDefaults("test"));
        Assert.assertEquals("invalid exception message",
                            "test matchMaxEpsilonFullScale must be defined", expectedException.getMessage());
    }

}