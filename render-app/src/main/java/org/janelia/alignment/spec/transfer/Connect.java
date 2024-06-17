package org.janelia.alignment.spec.transfer;

import java.io.Serializable;

/**
 * Connection information for render web services.
 *
 * @author Eric Trautman
 */
public class Connect implements Serializable {

    private final String host;
    private final Integer port;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private Connect() {
        this(null, null);
    }

    public Connect(final String host,
                   final Integer port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }
}
