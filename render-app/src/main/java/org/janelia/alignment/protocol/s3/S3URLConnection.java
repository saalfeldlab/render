package org.janelia.alignment.protocol.s3;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.AmazonS3URI;

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
    private AmazonS3URI s3uri;
    private AWSCredentialsProvider credentialsProvider;

    public S3URLConnection(URL url, AWSCredentialsProvider credentialsProvider) {
        super(url);
        s3uri = new AmazonS3URI(url.toString());
        this.credentialsProvider = credentialsProvider;
    }

    public void connect() throws IOException {
        try {
            AmazonS3 s3Client = new AmazonS3Client(credentialsProvider);
            s3object = s3Client.getObject(new GetObjectRequest(s3uri.getBucket(), s3uri.getKey()));
            connected = true;
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException: ");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Unable to communicate with S3)");
            System.out.println("Error Message: " + ace.getMessage());
        }
    }

    public String getContentType() {
        return s3object.getObjectMetadata().getContentType();
    }

    public InputStream getInputStream() throws IOException {
        if (!connected) {
            connect();
        }
        return s3object.getObjectContent();
    }
}
