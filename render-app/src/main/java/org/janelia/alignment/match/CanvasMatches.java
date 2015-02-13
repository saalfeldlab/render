package org.janelia.alignment.match;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;

/**
 * All weighted point correspondences between two canvases.
 *
 * @author Eric Trautman
 */
public class CanvasMatches implements Serializable {

    /** Z value for all source coordinates. */
    private final Double pz;

    /** Canvas identifier for all source coordinates. */
    private final String pId;

    /** Z value for all target coordinates. */
    private final Double qz;

    /** Canvas identifier for all target coordinates. */
    private final String qId;

    /** Weighted source-target point correspondences. */
    private final Matches matches;

    public CanvasMatches(Double pz,
                         String pId,
                         Double qz,
                         String qId,
                         Matches matches) {
        this.pz = pz;
        this.pId = pId;
        this.qz = qz;
        this.qId = qId;
        this.matches = matches;
    }

    public Double getPz() {
        return pz;
    }

    public String getpId() {
        return pId;
    }

    public Double getQz() {
        return qz;
    }

    @Override
    public String toString() {
        return "{pz: " + pz +
               ", pId: '" + pId + '\'' +
               ", qz: " + qz +
               ", qId: '" + qId + '\'' +
               '}';
    }

    public String toJson() {
        return JsonUtils.GSON.toJson(this);
    }

    public String toTabSeparatedFormat() {
        final double[][] ps = matches.getPs();
        final double[][] qs = matches.getQs();
        final double[] ws = matches.getWs();
        final StringBuilder sb = new StringBuilder(ws.length * 100);
        for (int i = 0; i < ws.length; i++) {
            sb.append(pz).append('\t').append(pId).append('\t').append(ps[0][i]).append('\t').append(ps[1][i]).append('\t');
            sb.append(qz).append('\t').append(qId).append('\t').append(qs[0][i]).append('\t').append(qs[1][i]).append('\t');
            sb.append(ws[i]).append('\n');
        }
        return sb.toString();
    }

    public static CanvasMatches fromJson(String json) {
        return JsonUtils.GSON.fromJson(json, CanvasMatches.class);
    }

}
