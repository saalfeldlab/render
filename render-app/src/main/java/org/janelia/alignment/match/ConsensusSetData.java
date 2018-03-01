package org.janelia.alignment.match;

import java.io.Serializable;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Information about a consensus set of matches.
 *
 * @author Eric Trautman
 */
@ApiModel(description = "Information about a consensus set of matches.")
@XmlAccessorType(XmlAccessType.FIELD)
public class ConsensusSetData implements Serializable {

    @ApiModelProperty(value = "Size-ordered index of the consensus set (0 for largest set of matches)", required = true)
    private Integer index;

    @ApiModelProperty(value = "Original canvas identifier for all source coordinates", required = true)
    private String originalPId;

    @ApiModelProperty(value = "Original canvas identifier for all target coordinates", required = true)
    private String originalQId;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private ConsensusSetData() {
    }

    /**
     * Basic constructor.
     *
     * @param  index        index of the size-ordered consensus set for these matches (0 for largest set).
     * @param  originalPId  original source canvas identifier.
     * @param  originalQId  original target canvas identifier.
     */
    public ConsensusSetData(final Integer index,
                            final String originalPId,
                            final String originalQId) {
        this.index = index;
        this.originalPId = originalPId;
        this.originalQId = originalQId;
    }

    public Integer getIndex() {
        return index;
    }

    public String getOriginalPId() {
        return originalPId;
    }

    public String getOriginalQId() {
        return originalQId;
    }
}
