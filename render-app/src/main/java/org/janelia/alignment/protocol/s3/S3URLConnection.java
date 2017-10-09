package org.janelia.alignment.protocol.s3;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3URI;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;

/**
 * Rough implementation of an S3 URL class handler.
 *
 * When registered, ImageJ's Opener will be able to retrieve "s3://" URLs.
 * ProfileCredentialsProvider is used if no alternate is provided.  This should handle most uses.
 *
 * Based on AWS S3 SDK sample code: http://docs.aws.amazon.com/AmazonS3/latest/dev/RetrievingObjectUsingJava.html
 */
public class S3URLConnection extends URLConnection {

    private S3Object s3object = null;
    private final AmazonS3URI s3uri;
    private final AWSCredentialsProvider credentialsProvider;

    public S3URLConnection(final URL url,
                           final AWSCredentialsProvider credentialsProvider) {
        super(url);
        s3uri = new AmazonS3URI(url.toString());
        this.credentialsProvider = credentialsProvider;
    }

    public void connect() throws IOException {
        try {
            final AmazonS3 s3Client = new AmazonS3Client(credentialsProvider);
            String s3key;
            try {
                s3key = java.net.URLDecoder.decode(s3uri.getKey(), "UTF-8");
            } catch (final UnsupportedEncodingException e) {
                // TODO: Better error handling with badly encoded URLs?
                s3key = s3uri.getKey();
            }

            s3object = s3Client.getObject(new GetObjectRequest(s3uri.getBucket(), s3key));
            connected = true;
        } catch (final AmazonServiceException ase) {
            throw new IOException("Amazon S3 service failure for error type " + ase.getErrorType(), ase);
        } catch (final AmazonClientException ace) {
            throw new IOException("Amazon S3 client failure", ace);
        }
    }

    public String getContentType() {
        return s3object.getObjectMetadata().getContentType();
    }

    public InputStream getInputStream() throws IOException {
        if (! connected) {
            connect();
        }
        return s3object.getObjectContent();
    }
}
