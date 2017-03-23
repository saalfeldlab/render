package org.janelia.alignment.protocol.s3;

import com.amazonaws.auth.AWSCredentialsProvider;
import ij.ImagePlus;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Locale;

/**
 * Wrapper to the ij.io.Opener for render.
 *
 * This is a kludge as -Djava.protocol.handler.pkgs=org.janelia.alignment.protocol does not seem to work with Jetty.
 */
public class S3Opener extends ij.io.Opener {

    private AWSCredentialsProvider credentialsProvider;

    public S3Opener() {
        this(null);
    }

    public S3Opener(AWSCredentialsProvider credentialsProvider) {
        super();
        this.credentialsProvider = credentialsProvider;
    }

    @Override
    public ImagePlus openURL(String url) {
        if (url.startsWith("s3://")) {
            ImagePlus imp = null;
            String name = "";
            int index = url.lastIndexOf('/');
            if (index>0)
                name = url.substring(index+1);
            try {
                URLStreamHandler handler = new S3Handler(credentialsProvider);
                URL u = new URL(null, url, handler);
                URLConnection uc = u.openConnection();
                String lurl = url.toLowerCase(Locale.US);
                if (lurl.endsWith(".jpg") || lurl.endsWith(".gif")) {
                    imp = openJpegOrGifUsingURL(name, u);
                } else if (lurl.endsWith(".png")) {
                    imp = openPngUsingURL(name, u);
                } else if (lurl.endsWith(".tif") || lurl.endsWith(".tiff")) {
                    imp = super.openTiff(u.openStream(), name);
                } else {
                    // TODO: Use mime-types from s3 instead of suffixes?
                    // String type = uc.getContentType();
                    throw new IOException("Unknown suffix on " + url);
                }
            } catch (IOException e) {
                System.out.println("Error loading url " + url + ": " + e.toString());
            }
            return imp;
        } else {
            return super.openURL(url);
        }
    }

    /* The following are based on protected methods from ij.io.Opener. */
    private ImagePlus openJpegOrGifUsingURL(String title, URL url) {
        Image img = Toolkit.getDefaultToolkit().createImage(url);
        return new ImagePlus(title, img);
    }

    ImagePlus openPngUsingURL(String title, URL url) throws IOException{
        Image img;
        InputStream in = url.openStream();
        img = ImageIO.read(in);
        return new ImagePlus(title, img);
    }



}
