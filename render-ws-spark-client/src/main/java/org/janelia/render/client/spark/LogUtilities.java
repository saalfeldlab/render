package org.janelia.render.client.spark;

import com.google.common.io.CharStreams;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.apache.log4j.PatternLayout;
import org.apache.spark.api.java.JavaSparkContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;

/**
 * Utility methods for managing logging on Spark nodes.
 *
 * @author Eric Trautman
 */
public class LogUtilities {

    public static void setupExecutorLog4j(final String context) {
        setupExecutorLog4j(context, "org.janelia");
    }

    public static void setupExecutorLog4j(final String context,
                                          final String rootLoggerName) {

        final Logger logger = LogManager.getLogger(rootLoggerName);

        for (final var e = LogManager.getRootLogger().getAllAppenders(); e.hasMoreElements(); ) {
            final Appender a = (Appender) e.nextElement();
            if (a instanceof ConsoleAppender) {
                final Layout layout = a.getLayout();
                if (layout instanceof final PatternLayout patternLayout) {
                    final String conversionPattern = "%d{ISO8601} [%t] [%X{context}] %-5p [%c] %m%n";
                    if (! conversionPattern.equals(patternLayout.getConversionPattern())) {
                        a.setLayout(new PatternLayout(conversionPattern));
                    }
                }
            }
        }

        MDC.put("context", context);

        logger.setLevel(Level.DEBUG);
    }

    /**
     * @param  loggerName  name of the logger to check.
     *
     * @return the level explicitly set for the specified logger, or null if the logger inherits
     *         its level or the bound logging framework is not supported.
     *
     * @see #setLogLevel
     */
    public static org.slf4j.event.Level getLogLevel(final String loggerName) {

        org.slf4j.event.Level level = null;

        final ILoggerFactory factory = LoggerFactory.getILoggerFactory();

        if (factory instanceof LoggerContext) {

            // Janelia Spark clusters use logback
            final ch.qos.logback.classic.Logger logger = ((LoggerContext) factory).getLogger(loggerName);
            if (logger != null) {
                level = toSlf4jLevel(logger.getLevel() == null ? null : logger.getLevel().toString());
            }

        } else if (LOG4J_LOGGER_FACTORY_CLASS_NAME.equals(factory.getClass().getName())) {

            // Google Dataproc Spark clusters use Log4j
            final org.apache.logging.log4j.Level log4jLevel =
                    org.apache.logging.log4j.LogManager.getLogger(loggerName).getLevel();
            level = toSlf4jLevel(log4jLevel == null ? null : log4jLevel.name());

        }

        return level;
    }

    /**
     * Sets the level for the specified logger, supporting the logback bindings used by
     * Janelia Spark clusters and the Log4j bindings used by Google Dataproc Spark clusters.
     * Nothing is changed (and nothing is thrown) when neither binding is in use, so callers
     * can always safely ask for reduced logging.
     *
     * @param  loggerName  name of the logger to change.
     * @param  logLevel    level to set (or null to make the logger inherit its parent's level).
     */
    public static void setLogLevel(final String loggerName,
                                   final org.slf4j.event.Level logLevel) {

        final ILoggerFactory factory = LoggerFactory.getILoggerFactory();

        if (factory instanceof LoggerContext) {

            // Janelia Spark clusters use logback
            final ch.qos.logback.classic.Logger logger = ((LoggerContext) factory).getLogger(loggerName);
            if (logger == null) {
                LOG.warn("setLogLevel: ignoring request because logback logger '{}' was not found", loggerName);
            } else {
                logger.setLevel(logLevel == null ?
                                null : ch.qos.logback.classic.Level.toLevel(logLevel.name()));
            }

        } else if (LOG4J_LOGGER_FACTORY_CLASS_NAME.equals(factory.getClass().getName())) {

            // Google Dataproc Spark clusters use Log4j
            org.apache.logging.log4j.core.config.Configurator.setLevel(
                    loggerName,
                    logLevel == null ? null : org.apache.logging.log4j.Level.getLevel(logLevel.name()));

        } else {

            LOG.warn("setLogLevel: ignoring request for logger '{}' because logger factory {} is not supported",
                     loggerName, factory.getClass().getName());

        }
    }

    /** @return the slf4j level with the specified name or null if there is no matching level. */
    private static org.slf4j.event.Level toSlf4jLevel(final String levelName) {
        org.slf4j.event.Level level = null;
        if (levelName != null) {
            try {
                level = org.slf4j.event.Level.valueOf(levelName);
            } catch (final IllegalArgumentException e) {
                // levels like OFF and ALL have no slf4j equivalent, so treat them as unset
                LOG.warn("toSlf4jLevel: ignoring unsupported level {}", levelName);
            }
        }
        return level;
    }

    /**
     * Tries to retrieve executors data from endpoints with ports 4040 - 4060.
     * This hack works with older Spark versions (e.g. 1.6.2).
     *
     * @param  appId  application ID.
     *
     * @return information about the executors in the current Spark context in JSON format.
     */
    public static String getExecutorsApiJson(final String appId) {

        String json = "";
        for (int port = 4040; port < 4060; port++) {

            json = getExecutorsApiJson(appId, "http://localhost:" + port);

            if (! json.startsWith(JSON_ERROR_PREFIX)) {
                break;
            }
        }

        return json;
    }

    /**
     * @return JSON formatted executors information for the specified context.
     */
    public static String getExecutorsApiJson(final JavaSparkContext sparkContext) {
        return getExecutorsApiJson(sparkContext.getConf().getAppId(),
                                   sparkContext.sc().uiWebUrl().get());
    }

    /**
     * @return JSON formatted executors information for the specified app and URL.
     */
    public static String getExecutorsApiJson(final String appId,
                                             final String apiUrl) {

        String json;

        final URL url;
        try {
            url = new URL(apiUrl + "/api/v1/applications/" + appId + "/executors");

            try (final BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()))) {
                json = CharStreams.toString(in);
            } catch (final Throwable t) {
                json = getErrorJson("failed to retrieve executors data", t);
            }

        } catch (final MalformedURLException e) {
            json = getErrorJson("bad executors URL", e);
        }

        return json;
    }

    /**
     * Logs Spark executor data for specified context.
     *
     * @param  sparkContext  context for current Spark job.
     */
    public static void logSparkClusterInfo(final JavaSparkContext sparkContext) {
        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = getExecutorsApiJson(sparkContext);
        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);
    }

    private static String getErrorJson(final String errorMessage,
                                       final Throwable cause) {
        return JSON_ERROR_PREFIX + errorMessage + "\", \"exception_message\": \"" + cause.getMessage() + "\" } ]";
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(LogUtilities.class);

    private static final String JSON_ERROR_PREFIX = "[ { \"error\": \"";

    /** Name of the slf4j logger factory class used when slf4j is bound to Log4j. */
    private static final String LOG4J_LOGGER_FACTORY_CLASS_NAME = "org.apache.logging.slf4j.Log4jLoggerFactory";
}
