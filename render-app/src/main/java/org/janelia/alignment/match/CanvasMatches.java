package org.janelia.alignment.match;

import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.janelia.alignment.json.JsonUtils;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * All weighted point correspondences between two canvases.
 *
 * @author Eric Trautman
 */
@ApiModel(value = "A canvas match is the set of all weighted point correspondences between two canvases.")
@XmlAccessorType(XmlAccessType.FIELD)
public class CanvasMatches implements Serializable {

    /** Group (or section) identifier for all source coordinates. */
    @ApiModelProperty(value = "Group (or section) identifier for all source coordinates",
                      required=true)
    private String pGroupId;

    /** Canvas (or tile) identifier for all source coordinates. */
    @ApiModelProperty(value = "Canvas (or tile) identifier for all source coordinates", required=true)
    private String pId;

    /** Group (or section) identifier for all target coordinates. */
    @ApiModelProperty(value = "Group (or section) identifier for all target coordinates", required=true)
    private String qGroupId;

    /** Canvas (or tile) identifier for all target coordinates. */
    @ApiModelProperty(value = "Canvas (or tile) identifier for all target coordinates", required=true)
    private String qId;

    /** Weighted source-target point correspondences. */
    @ApiModelProperty(value = "Weighted source-target point correspondences", required=true)
    private Matches matches;

    /**
     * Basic constructor that also normalizes (see {@link #normalize()}) p and q ordering.
     *
     * @param  pGroupId  group (or section) identifier for the source canvas (or tile).
     * @param  pId       identifier for the source canvas (or tile).
     * @param  qGroupId  group (or section) identifier for the target canvas (or tile).
     * @param  qId       identifier for the target canvas (or tile).
     * @param  matches   weighted source-target point correspondences.
     *
     * @throws IllegalArgumentException
     *   if any values are missing.
     */
    public CanvasMatches(final String pGroupId,
                         final String pId,
                         final String qGroupId,
                         final String qId,
                         final Matches matches)
            throws IllegalArgumentException {


        this.pGroupId = pGroupId;
        this.pId = pId;
        this.qGroupId = qGroupId;
        this.qId = qId;
        this.matches = matches;

        normalize();
    }

    /**
     * Ensures that for any two canvases (tiles), the source (p) and target (q) are consistently assigned.
     * This is done by using lexicographic ordering of the group and canvas ids.
     * Normalized source (p) identifiers will always lexicographically precede target (q) identifiers.
     *
     * @throws IllegalArgumentException
     *   if any values are missing.
     */
    public void normalize() throws IllegalArgumentException {

        if ((pGroupId == null) || (qGroupId == null) || (pId == null) || (qId == null)) {
            throw new IllegalArgumentException(
                    "CanvasMatches are missing required pGroupId, qGroupId, pId, and/or qId values");
        }

        final boolean isFlipNeeded;
        final int compareResult = pGroupId.compareTo(qGroupId);
        if (compareResult == 0) {
            isFlipNeeded = (pId.compareTo(qId) > 0);
        } else {
            isFlipNeeded = (compareResult > 0);
        }

        if (isFlipNeeded) {
            String swap = pGroupId;
            pGroupId = qGroupId;
            qGroupId = swap;

            swap = pId;
            pId = qId;
            qId = swap;

            if (matches != null) {
                matches = new Matches(matches.getQs(), matches.getPs(), matches.getWs());
            }
        }

    }

    public String getpGroupId() {
        return pGroupId;
    }

    public String getpId() {
        return pId;
    }

    public String getqGroupId() {
        return qGroupId;
    }

    public String getqId() {
        return qId;
    }

    @Override
    public String toString() {
        return "{pGroupId: " + pGroupId +
               ", pId: '" + pId + '\'' +
               ", qGroupId: " + qGroupId +
               ", qId: '" + qId + '\'' +
               '}';
    }

    public String toJson() {
        return JsonUtils.GSON.toJson(this);
    }

    @SuppressWarnings("UnusedDeclaration")
    public String toTabSeparatedFormat() {
        final double[][] ps = matches.getPs();
        final double[][] qs = matches.getQs();
        final double[] ws = matches.getWs();
        final StringBuilder sb = new StringBuilder(ws.length * 100);
        for (int i = 0; i < ws.length; i++) {
            sb.append(pGroupId).append('\t').append(pId).append('\t').append(ps[0][i]).append('\t').append(ps[1][i]).append('\t');
            sb.append(qGroupId).append('\t').append(qId).append('\t').append(qs[0][i]).append('\t').append(qs[1][i]).append('\t');
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
