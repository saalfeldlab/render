package org.janelia.render.client.multisem;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.multisem.MultiSemUtilities;

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
 *       groupId "1.0", p "w60_magc0399_scan004_m0013_r46_s01" and q "w60_magc0399_scan004_m0013_r47_s02"
 *       groupId "2.0", p "w60_magc0399_scan005_m0013_r46_s01" and q "w60_magc0399_scan005_m0013_r47_s02"
 *       groupId "3.0", p "w60_magc0399_scan006_m0013_r46_s01" and q "w60_magc0399_scan006_m0013_r47_s02"
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
     * (e.g. "2.0", "w60_magc0399_scan005_m0013_r46_s01" to "magc0399", "m0013_s01").
     *
     * @return multi-field-of-view position id derived from specified canvasId.
     */
    public static CanvasId toPositionCanvasId(final CanvasId canvasId) {

        return new CanvasId(canvasId.getId().substring(4, 12),                          // slab id: magc0399
                            MultiSemUtilities.getMfovSfovForTileId(canvasId.getId()));  // <mfov>_<sfov>: m0013_s01
    }

}
