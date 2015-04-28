package org.janelia.render.service.model.stack;

import java.io.Serializable;
import java.util.Date;

/**
 * Details about a specific version of stack.
 *
 * @author Eric Trautman
 */
public class StackVersion
        implements Serializable {

    private final Integer version;
    private final Date createDate;
    private final Date indexDate;
    private final String notes;
    private final StackStats stats;

    public StackVersion(Integer version,
                        Date createDate,
                        Date indexDate,
                        String notes,
                        StackStats stats) {
        this.version = version;
        this.createDate = createDate;
        this.indexDate = indexDate;
        this.notes = notes;
        this.stats = stats;
    }

    public Integer getVersion() {
        return version;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public Date getIndexDate() {
        return indexDate;
    }

    public String getNotes() {
        return notes;
    }

    public StackStats getStats() {
        return stats;
    }
}
