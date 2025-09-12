package org.janelia.alignment.multisem;

import java.io.FileReader;
import java.util.List;

import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.stack.StackId;

/**
 * Tests the {@link MultiSemMfovColumn} class.
 */
public class MultiSemMfovColumnTest {

    public static void main(final String[] args)
            throws Exception {

        final StackId stackId = new StackId("hess_wafers_60_61",
                                            "w60_serial_360_to_369",
                                            "w60_s365_r00_gc");
        final double z = 1.0;
        final List<TileBounds> boundsForEachSfovInZLayer =
                TileBounds.fromJsonArray(
                        new FileReader("/Users/trautmane/Desktop/w60_s365_r00_gc-z-1-tileBounds.json"));

        MultiSemMfovColumn.assembleColumnData(stackId,
                                              z,
                                              boundsForEachSfovInZLayer);
    }


}
