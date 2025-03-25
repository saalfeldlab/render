package org.janelia.alignment.loader;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension of {@link ImageJDefaultLoader} that wraps each load in
 * a Thread that will time out after {@link #TIMEOUT_SECONDS} seconds.
 */
public class ImageJDefaultLoaderWithTimeout
        extends ImageJDefaultLoader {

    /** Shareable instance of this loader. */
    public static final ImageJDefaultLoaderWithTimeout INSTANCE = new ImageJDefaultLoaderWithTimeout();

    public ImageProcessor load(final String urlString)
            throws IllegalArgumentException {

        ImagePlus imagePlus = null;
        for (int retryNumber = 0; retryNumber <= DEFAULT_MAX_RETRIES; retryNumber++) {
            imagePlus = loadImagePlusWithTimedThread(urlString, retryNumber, DEFAULT_MAX_RETRIES);
            if (imagePlus != null) {
                break;
            }
        }

        if (imagePlus == null) {
            throw new IllegalArgumentException("failed to load image from " + urlString);
        }

        return imagePlus.getProcessor();
    }

    private ImagePlus loadImagePlusWithTimedThread(final String urlString,
                                                   final int retryNumber,
                                                   final int maxRetries) {
        ImagePlus imagePlus = null;

        // TODO: consider using a shared Executors.newCachedThreadPool
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<ImagePlus> future =
                executor.submit(() -> super.loadImagePlus(urlString,
                                                          retryNumber,
                                                          maxRetries,
                                                          false));
        try {
            imagePlus = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException t) {
            future.cancel(true);
            LOG.info("loadImagePlusWithTimedThread: load of {} timed-out for retry number {} of {}",
                     urlString, retryNumber, maxRetries);
        } catch (final Throwable t) {
            LOG.info("loadImagePlusWithTimedThread: load of {} failed for retry number {} of {} with exception",
                     urlString, retryNumber, maxRetries, t);
        } finally {
            executor.shutdownNow();
        }

        return imagePlus;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImageJDefaultLoaderWithTimeout.class);

    private static final int TIMEOUT_SECONDS = 45;
}