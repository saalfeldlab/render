package org.janelia.render.client.request;

import java.io.IOException;
import java.net.UnknownHostException;

import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A retry handler that extends the standard one by including a 5 second wait between retries for unknown host
 * exception cases.  This was introduced to work around a January 2016 DNS issue at Janelia.
 *
 * @author Eric Trautman
 */
public class WaitingRetryHandler extends StandardHttpRequestRetryHandler {

    private final long waitMilliseconds;

    public WaitingRetryHandler() {
        this(5000);
    }

    public WaitingRetryHandler(final long waitMilliseconds) {
        this.waitMilliseconds = waitMilliseconds;
    }

    @Override
    public boolean retryRequest(final IOException exception,
                                final int executionCount,
                                final HttpContext context) {

        final boolean retry = super.retryRequest(exception, executionCount, context);

        if (retry && (exception instanceof UnknownHostException)) {
            LOG.info("retryRequest: waiting {}ms before retrying request that failed from UnknownHostException",
                     waitMilliseconds);
            try {
                Thread.sleep(waitMilliseconds);
            } catch (final InterruptedException ie) {
                LOG.warn("retryRequest: retry wait was interrupted", ie);
            }

        }

        return retry;
    }

    private static final Logger LOG = LoggerFactory.getLogger(WaitingRetryHandler.class);

}
