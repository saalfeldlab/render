package org.janelia.alignment.util;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import static org.slf4j.Logger.ROOT_LOGGER_NAME;

/**
 * Tools for manipulating logback logging levels from code.
 *
 * @author Eric Trautman
 */
public class LogbackTestTools {

    public static void setRootLogLevelToError() {
        setRootLogLevel(Level.ERROR);
    }

    public static void setRootLogLevel(final Level rootLogLevel) {
        setLogLevel(ROOT_LOGGER_NAME, rootLogLevel);
    }

    public static void setLogLevelToDebug(final String loggerName) {
        setLogLevel(loggerName, Level.DEBUG);
    }

    public static void setLogLevelToInfo(final String loggerName) {
        setLogLevel(loggerName, Level.INFO);
    }

    public static void setLogLevel(final String loggerName,
                                   final Level logLevel) {
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger logger = loggerContext.getLogger(loggerName);
        if (logger == null) {
            throw new IllegalArgumentException("logger with name '" + loggerName + "' not found");
        }
        logger.setLevel(logLevel);
    }
}
