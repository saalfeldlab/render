package org.janelia.alignment.spec.transfer;

import java.io.Serializable;

/**
 * Connection information for render web services.
 *
 * @author Eric Trautman
 */
public record Connect(String host, Integer port)
        implements Serializable {

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private Connect() {
        this(null, null);
    }
}
