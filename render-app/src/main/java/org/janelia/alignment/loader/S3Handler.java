package org.janelia.alignment.loader;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.kms.model.UnsupportedOperationException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * A java protocol handler for s3:// URLs.
 */
public class S3Handler extends URLStreamHandler {

    private final AmazonS3 s3Client;

    @SuppressWarnings("unused")
    public S3Handler() {
        throw new UnsupportedOperationException(
                "lazy load of s3 client needs to be re-thought if we need this default constructor");
    }

    public S3Handler(final AmazonS3 s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public URLConnection openConnection(final java.net.URL url) {
        return new S3URLConnection(url, s3Client);
    }

    private static S3Handler SHARED_INSTANCE = null;

    public static boolean isS3Protocol(final String urlString) {
        return urlString.startsWith("s3://");
    }

    public static URL getUrl(final String urlString)
            throws IOException {
        return new URL(null, urlString, getSharedInstance());
    }

    public static S3Handler getSharedInstance()
            throws IOException {
        if (SHARED_INSTANCE == null) {
            buildInstance();
        }
        return SHARED_INSTANCE;
    }

    private static synchronized void buildInstance() throws IOException {
        if (SHARED_INSTANCE == null) {
            try {
                final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();
                final AmazonS3 s3Client = AmazonS3ClientBuilder.standard().withCredentials(credentialsProvider).build();
                SHARED_INSTANCE = new S3Handler(s3Client);
            } catch (final AmazonServiceException ase) {
                throw new IOException("Amazon S3 service failure for error type " + ase.getErrorType(), ase);
            } catch (final AmazonClientException ace) {
                throw new IOException("Amazon S3 client failure", ace);
            }
        }
    }

}
