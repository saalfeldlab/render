package org.janelia.alignment.match.parameters;

import org.junit.Test;

/**
 * Tests the {@link GeometricDescriptorAndMatchFilterParameters} class.
 *
 * @author Eric Trautman
 */
public class GeometricDescriptorAndMatchFilterParametersTest {

    @Test
    public void testValidateAndSetDefaults() {
        final GeometricDescriptorAndMatchFilterParameters parameters = new GeometricDescriptorAndMatchFilterParameters();
        parameters.validateAndSetDefaults();
        // default parameters with gdEnabled = false should be valid
    }

}