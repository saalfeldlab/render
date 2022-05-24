package org.janelia.render.client.zspacing;

import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link ThicknessCorrectionData} class.
 *
 * @author Eric Trautman
 */
public class ThicknessCorrectionDataTest {

    private final String smallCorrectionZCoords = "4055 4054.926248674371\n" +
                                                  "4056 4055.890848802147\n" +
                                                  "4057 4056.8086632669383";
    @Test
    public void testGetInterpolatorForSmallCorrection() {

        final String context = "small correction";
        final ThicknessCorrectionData data = buildData(smallCorrectionZCoords);

        validateInterpolator(context, data, 4055, 4055, 0.92, 4056);
        validateInterpolator(context, data, 4056, 4056, 0.88, 4057);
    }

    @Test
    public void testGetInterpolatorForBigCorrection() {

        final String zCoordsText =
                "4100 4102.787808503473\n" +
                "4101 4103.020253943293\n" +
                "4102 4103.020353945556\n" +
                "4103 4104.076545863985\n" +
                "4104 4104.076645866249\n" +
                "4105 4104.939207547616\n" +
                "4106 4104.93930754988\n" +
                "4107 4104.939407552144\n" +
                "4108 4104.939507554407\n" +
                "4109 4104.939607556671\n" +
                "4110 4105.672175672366\n" +
                "4111 4107.959931776086\n" +
                "4112 4118.374343051017\n" +
                "4113 4119.268355304987\n" +
                "4114 4120.590465478949";

        final String context = "big correction";
        final ThicknessCorrectionData data = buildData(zCoordsText);

        validateInterpolator(context, data, 4103, 4100, 0.09, 4101);
        validateInterpolator(context, data, 4104, 4102, 0.07, 4103);
        validateInterpolator(context, data, 4105, 4109, 0.92, 4110);
        validateInterpolator(context, data, 4106, 4110, 0.85, 4111);
        validateInterpolator(context, data, 4107, 4110, 0.42, 4111);
        validateInterpolator(context, data, 4108, 4111, 0.99, 4112);
        validateInterpolator(context, data, 4109, 4111, 0.90, 4112);
        validateInterpolator(context, data, 4110, 4111, 0.80, 4112);
        validateInterpolator(context, data, 4111, 4111, 0.71, 4112);
        validateInterpolator(context, data, 4118, 4111, 0.04, 4112);
        validateInterpolator(context, data, 4119, 4112, 0.3, 4113);
        validateInterpolator(context, data, 4120, 4113, 0.45, 4114);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetInterpolatorTooSmall() {
        final ThicknessCorrectionData data = buildData(smallCorrectionZCoords);
        data.getInterpolator(4054);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnorderedData() {
        final String unorderedZCoords = "4055 4054.926248674371\n" +
                                        "4056 4055.890848802147\n" +
                                        "4053 4053.8086632669383";
        buildData(unorderedZCoords);
    }

    private ThicknessCorrectionData buildData(final String zCoordsText) {
        final List<String> zCoordsList = Arrays.asList(zCoordsText.split("\n"));
        return new ThicknessCorrectionData(zCoordsList);
    }

    private void validateInterpolator(final String context,
                                      final ThicknessCorrectionData data,
                                      final long renderZ,
                                      final long expectedPriorZ,
                                      final double expectedPriorWeight,
                                      final long expectedNextZ) {

        final ThicknessCorrectionData.LayerInterpolator interpolator = data.getInterpolator(renderZ);

        Assert.assertEquals(context + ": incorrect priorStackZ for renderZ " + renderZ,
                            expectedPriorZ, interpolator.getPriorStackZ());
        Assert.assertEquals(context + ": incorrect priorWeight for renderZ " + renderZ,
                            expectedPriorWeight, interpolator.getPriorWeight(), 0.01);
        Assert.assertEquals(context + ": incorrect nextStackZ for renderZ " + renderZ,
                            expectedNextZ, interpolator.getNextStackZ());
    }
}
