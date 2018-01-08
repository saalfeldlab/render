package org.janelia.render.client;

import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command line web service client wrapper that logs unexpected exceptions
 * and overall process completion events.
 *
 * Original purpose for wrapper is to simplify detection of clients that
 * are abnormally terminated (e.g. by some other process).
 *
 * @author Eric Trautman
 */
public abstract class ClientRunner {

    private final String[] args;

    /**
     * @param  args  command line arguments for client.
     */
    public ClientRunner(final String[] args) {
        this.args = args;
    }

    /**
     * Wraps a run with consistent log statements.
     * Absence of the standard exit log message indicates that the client was terminated abnormally.
     */
    public void run() {

        LOG.info("run: entry");

        final ProcessTimer processTimer = new ProcessTimer();

        try {
            runClient(args);
            LOG.info("run: exit, processing completed in {}", processTimer);
            System.exit(0);
        } catch (final Throwable t) {
            LOG.error("run: caught exception", t);
            LOG.info("run: exit, processing failed after {}", processTimer);
            System.exit(1);
        }

    }

    /**
     * This method should contain the specific client implementation to be wrapped.
     *
     * @param  args  command line arguments for client.
     *
     * @throws Exception
     *   if the client fails for any reason.
     */
    public abstract void runClient(final String[] args) throws Exception ;

    private static final Logger LOG = LoggerFactory.getLogger(ClientRunner.class);
}
