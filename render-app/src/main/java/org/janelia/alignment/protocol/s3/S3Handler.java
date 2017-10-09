package org.janelia.alignment.protocol.s3;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;

import java.net.URLStreamHandler;
import java.net.URLConnection;

/**
 * A java protocol handler for s3:// URLs.
 */
public class S3Handler extends URLStreamHandler {
    private AWSCredentialsProvider credentialsProvider;

    public S3Handler() {
        this(new DefaultAWSCredentialsProviderChain());
    }

    public S3Handler(AWSCredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
    }

    @Override
    public URLConnection openConnection(java.net.URL url) throws java.io.IOException {
        return new S3URLConnection(url, credentialsProvider);
    }
}
