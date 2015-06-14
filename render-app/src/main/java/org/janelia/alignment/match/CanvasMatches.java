package org.janelia.alignment.match;

import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * All weighted point correspondences between two canvases.
 *
 * @author Eric Trautman
 */
public class CanvasMatches implements Serializable {

    /** Section identifier for all source coordinates. */
    private final String pSectionId;

    /** Canvas identifier for all source coordinates (e.g. tile id). */
    private final String pId;

    /** Section identifier for all target coordinates. */
    private final String qSectionId;

    /** Canvas identifier for all target coordinates (e.g. tile id). */
    private final String qId;

    /** Weighted source-target point correspondences. */
    private final Matches matches;

    public CanvasMatches(final String pSectionId,
                         final String pId,
                         final String qSectionId,
                         final String qId,
                         final Matches matches) {
        this.pSectionId = pSectionId;
        this.pId = pId;
        this.qSectionId = qSectionId;
        this.qId = qId;
        this.matches = matches;
    }

    public String getpSectionId() {
        return pSectionId;
    }

    public String getpId() {
        return pId;
    }

    public String getqSectionId() {
        return qSectionId;
    }

    @Override
    public String toString() {
        return "{pSectionId: " + pSectionId +
               ", pId: '" + pId + '\'' +
               ", qSectionId: " + qSectionId +
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
            sb.append(pSectionId).append('\t').append(pId).append('\t').append(ps[0][i]).append('\t').append(ps[1][i]).append('\t');
            sb.append(qSectionId).append('\t').append(qId).append('\t').append(qs[0][i]).append('\t').append(qs[1][i]).append('\t');
            sb.append(ws[i]).append('\n');
        }
        return sb.toString();
    }

    public static CanvasMatches fromJson(final String json) {
        return JsonUtils.GSON.fromJson(json, CanvasMatches.class);
    }

    public static List<CanvasMatches> fromJsonArray(final Reader json) {
        return JsonUtils.GSON.fromJson(json, LIST_TYPE);
    }

    private static final Type LIST_TYPE = new TypeToken<List<CanvasMatches>>(){}.getType();
}
