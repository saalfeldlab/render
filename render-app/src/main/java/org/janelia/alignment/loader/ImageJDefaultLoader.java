package org.janelia.alignment.loader;

import com.google.common.net.MediaType;

import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;

import java.awt.Image;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default loader that wraps the ij.io.Opener for render and handles S3 URLs.
 */
public class ImageJDefaultLoader
        implements ImageLoader {

    /** Shareable instance of this loader. */
    public static final ImageJDefaultLoader INSTANCE = new ImageJDefaultLoader();

    /**
     * @return true (always) because the default ImageJ loader handles 2D sources.
     */
    @Override
    public boolean hasSame3DContext(final ImageLoader otherLoader) {
        return true;
    }

    public ImageProcessor load(final String urlString)
            throws IllegalArgumentException {
        return loadWithRetries(urlString, 0).getProcessor();
    }

    private ImagePlus loadWithRetries(final String urlString,
                                      final int retryNumber)
            throws IllegalArgumentException {

        final int maxRetries = 3;
        final int secondsBetweenRetries = 5;
        final int nextRetryNumber = retryNumber + 1;

        if (retryNumber > 0) {
            try {
                Thread.sleep(secondsBetweenRetries * 1000);
            } catch (final InterruptedException e) {
                LOG.warn("loadWithRetries: interrupted while sleeping before retry, continuing with retry now ", e);
            }
        }

        ImagePlus imagePlus;

        // openers keep state about the file being opened, so we need to create a new opener for each load
        final Opener opener = new Opener();
        opener.setSilentMode(true);

        try {
            if (S3Handler.isS3Protocol(urlString)) {

                String name = "";
                final int index = urlString.lastIndexOf('/');
                if (index > 0) {
                    name = urlString.substring(index + 1);
                }

                final URL u = S3Handler.getUrl(urlString);
                final URLConnection uc = u.openConnection();

                // assumes content type is always available, should be ok
                final MediaType contentType = MediaType.parse(uc.getContentType());

                final String lowerCaseUrl = urlString.toLowerCase(Locale.US);

                // honor content type over resource naming conventions, check for most common source image types first
                if (contentType.equals(MediaType.TIFF)) {
                    imagePlus = opener.openTiff(u.openStream(), name);
                } else if (contentType.equals(MediaType.PNG)) {
                    imagePlus = openPngUsingURL(name, u);
                } else if (contentType.equals(MediaType.JPEG) || contentType.equals(MediaType.GIF)) {
                    imagePlus = openJpegOrGifUsingURL(name, u);
                } else if (lowerCaseUrl.endsWith(".tif") || lowerCaseUrl.endsWith(".tiff")) {
                    imagePlus = opener.openTiff(u.openStream(), name);
                } else if (lowerCaseUrl.endsWith(".png")) {
                    imagePlus = openPngUsingURL(name, u);
                } else if (lowerCaseUrl.endsWith(".jpg") || lowerCaseUrl.endsWith(".gif")) {
                    imagePlus = openJpegOrGifUsingURL(name, u);
                } else {
                    throw new IOException("unsupported content type " + contentType + " for " + urlString);
                }

            } else {
                imagePlus = opener.openURL(urlString);
            }

        } catch (final Throwable t) {
            if (nextRetryNumber <= maxRetries) {
                LOG.warn("loadWithRetries: failed to load {}, will run retry number {} of {} in {} seconds",
                         urlString, nextRetryNumber, maxRetries, secondsBetweenRetries, t);
                imagePlus = loadWithRetries(urlString,
                                            nextRetryNumber);
            } else {
                throw new IllegalArgumentException(
                        getErrorMessage(urlString) + " after " + retryNumber + " retries",
                        t);
            }
        }

        if (imagePlus == null) {
            if (nextRetryNumber <= maxRetries) {
                LOG.warn("loadWithRetries: null imagePlus for {}, will run retry number {} of {} in {} seconds",
                         urlString, nextRetryNumber, maxRetries, secondsBetweenRetries);
                imagePlus = loadWithRetries(urlString,
                                            nextRetryNumber);
            } else {
                throw new IllegalArgumentException(
                        getErrorMessage(urlString) + " (null imagePlus) after " + retryNumber + " retries");
            }
        }

        return imagePlus;
    }

    /** Copied from protected {@link Opener#openJpegOrGifUsingURL}. */
    @SuppressWarnings("JavadocReference")
    public static ImagePlus openJpegOrGifUsingURL(final String title,
                                                  final URL url) {
        final Image img = Toolkit.getDefaultToolkit().createImage(url);
        return new ImagePlus(title, img);
    }

    /** Copied from protected {@link Opener#openPngUsingURL}. */
    @SuppressWarnings("JavadocReference")
    public static ImagePlus openPngUsingURL(final String title,
                                            final URL url)
            throws IOException {
        final InputStream in = url.openStream();
        final Image img = ImageIO.read(in);
        return new ImagePlus(title, img);
    }

    private String getErrorMessage(final String urlString) {
        return "failed to create imagePlus instance for '" + urlString + "'";
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImageJDefaultLoader.class);

}