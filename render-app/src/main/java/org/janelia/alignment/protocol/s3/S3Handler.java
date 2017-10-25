package org.janelia.alignment.protocol.s3;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.kms.model.UnsupportedOperationException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import java.io.IOException;
import java.net.URLStreamHandler;
import java.net.URLConnection;

/**
 * A java protocol handler for s3:// URLs.
 */
public class S3Handler extends URLStreamHandler {

    private final AmazonS3 s3Client;

    public S3Handler() {
        throw new UnsupportedOperationException("lazy load of s3 client needs to be re-thought if we need this default constructor");
    }

    public S3Handler(final AmazonS3 s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public URLConnection openConnection(final java.net.URL url) throws java.io.IOException {
        return new S3URLConnection(url, s3Client);
    }
}
