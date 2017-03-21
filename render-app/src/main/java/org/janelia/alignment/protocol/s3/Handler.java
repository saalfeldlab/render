package org.janelia.alignment.protocol.s3;

import java.net.URLStreamHandler;

/**
 * A java protocol handler for s3:// URLs.
 *
 * To use, add org.janelia.alignment.protocol to java.protocol.handler.pkgs, e.g.:
 * java -Djava.protocol.handler.pkgs=org.janelia.alignment.protocol ...
 */
public class Handler extends URLStreamHandler {
    protected java.net.URLConnection openConnection(java.net.URL url) throws java.io.IOException {
        return new S3URLConnection( url );
    }
}
