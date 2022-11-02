package org.janelia.render.client.multisem;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.OrderedCanvasIdPair;

/**
 * Identifies a pair of (single-field-of-view) tiles within a multi-field-of-view group
 * across all z-layers in a slab.
 *
 * <pre>
 * For example:
 *     position pair:
 *       groupId "001", p "000006_019" and q "000006_037"
 *
 *     identifies tile pairs:
 *       ...
 *       groupId "1247.0", p "001_000006_019_20220407_115555.1247.0" and q "001_000006_037_20220407_115555.1247.0"
 *       groupId "1248.0", p "001_000006_019_20220407_172027.1248.0" and q "001_000006_037_20220407_172027.1248.0"
 *       groupId "1249.0", p "001_000006_019_20220407_224819.1249.0" and q "001_000006_037_20220407_224819.1249.0"
 *       ...
 * </pre>
 *
 * @author Eric Trautman
 */
public class MFOVPositionPair extends OrderedCanvasIdPair {

    public MFOVPositionPair(final OrderedCanvasIdPair pair)
            throws IllegalArgumentException {
        super(toPositionCanvasId(pair.getP()),
              toPositionCanvasId(pair.getQ()),
              0.0);
    }

    /**
     * Converts canvasId based upon section and tile to multi-field-of-view position id based upon slab and mfov_sfov
     * (e.g. "1247.0", "001_000006_019_20220407_115555.1247.0" to "001", "000006_019").
     *
     * @return multi-field-of-view position id derived from specified canvasId.
     */
    public static CanvasId toPositionCanvasId(final CanvasId canvasId) {
        return new CanvasId(canvasId.getId().substring(0, 3),   // slab id
                            canvasId.getId().substring(5, 14)); // <mfov>_<sfov>
    }

}
