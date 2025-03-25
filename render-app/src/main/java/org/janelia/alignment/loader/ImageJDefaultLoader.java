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

    /** The maximum number of retries for each load. */
    public static int DEFAULT_MAX_RETRIES = 3;

    /**
     * @return true (always) because the default ImageJ loader handles 2D sources.
     */
    @Override
    public boolean hasSame3DContext(final ImageLoader otherLoader) {
        return true;
    }

    public ImageProcessor load(final String urlString)
            throws IllegalArgumentException {
        return loadImagePlus(urlString, 0, DEFAULT_MAX_RETRIES, true).getProcessor();
    }

    /**
     * Loads an imagePlus from the specified URL and potentially retries failed loads.
     *
     * @param  urlString      the URL of the image to load.
     * @param  retryNumber    number of the current retry (0 for the first load).
     * @param  maxRetries     maximum number of times to retry a failed load.
     * @param  retryAsNeeded  true indicates that failed load retries should be handled by this method and
     *                        false indicates that the caller will handle retries.
     *
     * @return ImagePlus instance for the specified URL.
     *
     * @throws IllegalArgumentException
     *   if the imagePlus cannot be created for any reason.
     */
    protected ImagePlus loadImagePlus(final String urlString,
                                      final int retryNumber,
                                      final int maxRetries,
                                      final boolean retryAsNeeded)
            throws IllegalArgumentException {

        final int nextRetryNumber = retryNumber + 1;

        if (retryNumber > 0) {
            try {
                Thread.sleep(getRetrySleepSeconds(retryNumber) * 1000L);
            } catch (final Throwable t) {
                LOG.info("loadImagePlus: exception thrown while sleeping before retry, continuing with retry now ", t);
            }
        } else if (retryNumber < 0) {
            throw new IllegalArgumentException("retryNumber '" + retryNumber + "' must be greater than or equal to 0");
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
                if (retryAsNeeded) {
                    LOG.info("loadImagePlus: failed to load {}, will run retry number {} of {} in {} seconds",
                             urlString, nextRetryNumber, maxRetries, getRetrySleepSeconds(nextRetryNumber), t);
                    imagePlus = loadImagePlus(urlString,
                                              nextRetryNumber,
                                              maxRetries,
                                              true);
                } else {
                    LOG.info("loadImagePlus: failed to load {} on retry number {} of {}",
                             urlString, retryNumber, maxRetries, t);
                    imagePlus = null;
                }
            } else {
                throw new IllegalArgumentException(
                        getErrorMessage(urlString) + " after " + retryNumber + " retries",
                        t);
            }
        }

        if (imagePlus == null) {
            if (nextRetryNumber <= maxRetries) {
                if (retryAsNeeded) {
                    LOG.info("loadImagePlus: null imagePlus for {}, will run retry number {} of {} in {} seconds",
                             urlString, nextRetryNumber, maxRetries, getRetrySleepSeconds(nextRetryNumber));
                    imagePlus = loadImagePlus(urlString,
                                              nextRetryNumber,
                                              maxRetries,
                                              true);
                } else {
                    LOG.info("loadImagePlus: null imagePlus for {} on retry number {} of {}",
                             urlString, retryNumber, maxRetries);
                }
            } else {
                throw new IllegalArgumentException(
                        getErrorMessage(urlString) + " (null imagePlus) after " + retryNumber + " retries");
            }
        }

        return imagePlus;
    }

    private int getRetrySleepSeconds(final int retryNumber) {
        return retryNumber > 0 ? 5 << (retryNumber - 1) : 0; // retry 1: 5s, retry 2: 10s, retry 3: 20s
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