package org.janelia.render.client.intensityadjust;

import java.util.List;

import org.janelia.alignment.spec.TileSpec;

/**
 * Tests the {@link IntensityCorrectionWorker} class.
 *
 * @author Eric Trautman
 */
public class IntensityCorrectiuonWorkerTest {

    public static void main(final String[] args) {

        final List<TileSpec> adjustedTilesSpecs;
        try {
            adjustedTilesSpecs = IntensityCorrectionWorker.deriveCrossLayerIntensityFilterData(
                    "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                    "reiser",
                    "Z0422_05_Ocellar",
                    "v7_acquire_align_ic",
                    5827,
                    200);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }

        // printed tile specs will contain intensity correction filter data ...
        adjustedTilesSpecs.forEach(ts -> System.out.println(ts.toJson()));
    }

}
