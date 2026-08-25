package org.janelia.alignment.filter;

import org.janelia.alignment.filter.emshading.QuadraticShading;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the {@link FilterSpec} class.
 *
 * @author Eric Trautman
 */
public class FilterSpecTest {

    @Test
    public void testJsonProcessing() {

        // --------------------------------
        // test CLAHE filter

        final CLAHE originalFilter = new CLAHE(false, 499, 255, 2.2f);

        FilterSpec filterSpec = FilterSpec.forFilter(originalFilter);

        Filter parsedInstance = parseAndBuildFilter(filterSpec);

        if (parsedInstance instanceof final CLAHE clahe) {
            assertEquals(originalFilter.getBlockRadius(), clahe.getBlockRadius(),
                         "invalid block radius parsed");
        } else {
            assertEquals(CLAHE.class, parsedInstance.getClass(),
                         "invalid instance created");
        }

        // --------------------------------
        // test LinearIntensityMapFilter

        final int numberOfRegionRows = 2;
        final int numberOfRegionColumns = 3;
        final int coefficientsPerRegion = 2;
        final double[][] coefficients = new double[][] {
                {1.1, 1.2}, {1.3, 1.4}, {1.5, 1.6},
                {2.1, 2.2}, {2.3, 2.4}, {2.5, 2.6}
        };
        final LinearIntensityMap8BitFilter originalMapFilter = new LinearIntensityMap8BitFilter(numberOfRegionRows,
                                                                                                numberOfRegionColumns,
                                                                                                coefficientsPerRegion,
                                                                                                coefficients);

        filterSpec = FilterSpec.forFilter(originalMapFilter);
        parsedInstance = parseAndBuildFilter(filterSpec);

        if (parsedInstance instanceof final LinearIntensityMap8BitFilter mapFilter) {
            final double[][] parsedCoefficients = mapFilter.getCoefficients();
            assertEquals(numberOfRegionRows, mapFilter.getNumberOfRegionRows(),
                         "invalid number of regions parsed");
            assertEquals(numberOfRegionRows * numberOfRegionColumns, parsedCoefficients.length,
                         "invalid number of coefficients parsed");
        } else {
            assertEquals(LinearIntensityMap8BitFilter.class, parsedInstance.getClass(),
                         "invalid instance created");
        }

    }

    private static Filter parseAndBuildFilter(final FilterSpec filterSpec) {
        final String json = filterSpec.toJson();
        assertNotNull(json,
                      "json generation returned null string for " + filterSpec.getClassName());

        final FilterSpec parsedSpec = FilterSpec.fromJson(json);
        assertNotNull(parsedSpec,
                      "null spec returned from json parse of " + filterSpec.getClassName());

        return parsedSpec.buildInstance();
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    public void compositeFilterSpecBuildsCorrectly() {
        FilterSpec first = getTestFilterSpec(1);
        FilterSpec second = getTestFilterSpec(2);

        // combine filters in all possible ways
        first = FilterSpec.combine(first, null);
        second = FilterSpec.combine(null, second);

        FilterSpec accumulate = FilterSpec.combine(first, second);

        accumulate = FilterSpec.combine(first, accumulate);
        accumulate = FilterSpec.combine(accumulate, second);

        accumulate = FilterSpec.combine(accumulate, accumulate);

        // check if it can be built and has the correct number of parameters
        final Filter recoveredFilter = accumulate.buildInstance();
        assertEquals(8, recoveredFilter.toParametersMap().size());
    }

    private static FilterSpec getTestFilterSpec(final int i) {
        final QuadraticShading quadraticShading = new QuadraticShading(new double[]{i, 0, 0, 0, 0, 0});
        final Filter filter = new ShadingCorrectionFilter(quadraticShading);
        return FilterSpec.forFilter(filter);
    }
}
