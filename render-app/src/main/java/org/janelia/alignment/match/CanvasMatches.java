package org.janelia.alignment.match;

import com.google.common.base.Objects;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.util.Arrays;
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
@ApiModel(description = "The set of all weighted point correspondences between two canvases.")
@XmlAccessorType(XmlAccessType.FIELD)
public class CanvasMatches implements Serializable, Comparable<CanvasMatches> {

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

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private CanvasMatches() {
    }

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

    /**
     * Appends the specified matches to this list.
     *
     * @param  additionalMatches  matches to append.
     */
    public void append(final Matches additionalMatches) {
        if (matches == null) {
            matches = new Matches(additionalMatches.getPs(),
                                  additionalMatches.getQs(),
                                  additionalMatches.getWs());
        } else {
            matches = new Matches(addAll(matches.getPs(), additionalMatches.getPs()),
                                  addAll(matches.getQs(), additionalMatches.getQs()),
                                  addAll(matches.getWs(), additionalMatches.getWs()));
        }
    }

    @Override
    public boolean equals(final Object o) {
        final boolean result;
        if (this == o) {
            result = true;
        } else if (o instanceof CanvasMatches) {
            final CanvasMatches that = (CanvasMatches) o;
            result = this.pGroupId.equals(that.pGroupId) &&
                     this.qGroupId.equals(that.qGroupId) &&
                     this.pId.equals(that.pId) &&
                     this.qId.equals(that.qId);
        } else {
            result = false;
        }
        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(pGroupId, qGroupId, pId, qId);
    }

    @Override
    public int compareTo(@SuppressWarnings("NullableProblems") final CanvasMatches that) {
        int result = this.pGroupId.compareTo(that.pGroupId);
        if (result == 0) {
            result = this.qGroupId.compareTo(that.qGroupId);
            if (result == 0) {
                result = this.pId.compareTo(that.pId);
                if (result == 0) {
                    result = this.qId.compareTo(that.qId);
                }
            }
        }
        return result;
    }

    public int size() {
        int size = 0;
        if (matches != null) {
            final double[] w = matches.getWs();
            if (w != null) {
                size = w.length;
            }
        }
        return size;
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

    public Matches getMatches() {
        return matches;
    }

    @Override
    public String toString() {
        return "{pGroupId: " + pGroupId +
               ", pId: '" + pId + '\'' +
               ", qGroupId: " + qGroupId +
               ", qId: '" + qId + '\'' +
               '}';
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

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static CanvasMatches fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    public static CanvasMatches fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    public static List<CanvasMatches> fromJsonArray(final String json) {
        // TODO: verify using Arrays.asList optimization is actually faster
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, CanvasMatches[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<CanvasMatches> fromJsonArray(final Reader json) {
        // TODO: verify using Arrays.asList optimization is actually faster
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, CanvasMatches[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final JsonUtils.Helper<CanvasMatches> JSON_HELPER =
            new JsonUtils.Helper<>(CanvasMatches.class);

    private static double[][] addAll(final double[][] array1, final double[][] array2) {
        final double[][] joinedArray = new double[array1.length][];
        for (int i = 0; i < joinedArray.length; i++) {
            joinedArray[i] = addAll(array1[i], array2[i]);
        }
        return joinedArray;
    }

    private static double[] addAll(final double[] array1, final double[] array2) {
        final double[] joinedArray = new double[array1.length + array2.length];
        System.arraycopy(array1, 0, joinedArray, 0, array1.length);
        System.arraycopy(array2, 0, joinedArray, array1.length, array2.length);
        return joinedArray;
    }
}
