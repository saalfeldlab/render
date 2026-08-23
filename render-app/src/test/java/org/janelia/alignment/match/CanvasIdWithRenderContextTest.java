package org.janelia.alignment.match;

import org.janelia.alignment.RenderParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the {@link RenderParameters} class.
 *
 * @author Eric Trautman
 */
public class CanvasIdWithRenderContextTest {

    @Test
    public void testGetClippedRenderParametersCopy() {

        final int fullWidth = 3840;
        final int fullHeight = 3840;

        final int clipWidth = 20;
        final int clipHeight = 10;

        final double leftXOffset = fullWidth - clipWidth;
        final double topYOffset = fullHeight - clipHeight;

        final Object[][] testData = {
                // relative position,             expectedWidth, expectedHeight, expectedX,   expectedY
                { MontageRelativePosition.TOP,    fullWidth,     clipHeight,     0.0,         topYOffset },
                { MontageRelativePosition.BOTTOM, fullWidth,     clipHeight,     0.0,         0.0        },
                { MontageRelativePosition.LEFT,   clipWidth,     fullHeight,     leftXOffset, 0.0        },
                { MontageRelativePosition.RIGHT,  clipWidth,     fullHeight,     0.0,         0.0        }
        };

        for (final Object[] data : testData) {

            final RenderParameters clippedParameters =
                    new RenderParameters("renderer-test.json", 0, 0, fullWidth, fullHeight, 1.0);

            final CanvasId canvasId = new CanvasId("groupA", "tileB", (MontageRelativePosition) data[0]);
            canvasId.setClipOffsets(fullWidth, fullHeight, clipWidth, clipHeight);

            CanvasIdWithRenderContext.clipRenderParameters(canvasId,
                                                           clipWidth,
                                                           clipHeight,
                                                           clippedParameters);

            assertEquals(data[1], clippedParameters.getWidth(),
                         "invalid clipped width for " + canvasId);
            assertEquals(data[2], clippedParameters.getHeight(),
                         "invalid clipped height for " + canvasId);
            assertEquals((Double) data[3], clippedParameters.getX(), 0.001,
                         "invalid x for " + canvasId);
            assertEquals((Double) data[4], clippedParameters.getY(), 0.001,
                         "invalid y for " + canvasId);
        }
    }

}
