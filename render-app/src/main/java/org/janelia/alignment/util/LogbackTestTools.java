package org.janelia.alignment.util;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;

import static org.slf4j.Logger.ROOT_LOGGER_NAME;

/**
 * Tools for manipulating logback logging levels from code.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("unused")
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

    public static void setRootFileAppenderWithTimestamp(final File logDirectory,
                                                        final String logFileNamePrefix) {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        final String logFileName = logFileNamePrefix + "." + sdf.format(new Date()) + ".log";
        final File logFile = new File(logDirectory, logFileName);
        setRootFileAppender(logFile);
    }

    public static void setRootFileAppender(final File logFile) {
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger rootLogger = loggerContext.getLogger(ROOT_LOGGER_NAME);
        final ConsoleAppender<ILoggingEvent> stdoutAppender =
                (ConsoleAppender<ILoggingEvent>) rootLogger.getAppender(CONSOLE_APPENDER_NAME);

        // detach previous test file appender if it already exists
        if (rootLogger.getAppender(TEST_ROOT_FILE_APPENDER_NAME) != null) {
            rootLogger.detachAppender(TEST_ROOT_FILE_APPENDER_NAME);
        }

        final FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setName(TEST_ROOT_FILE_APPENDER_NAME);
        fileAppender.setFile(logFile.getAbsolutePath());
        fileAppender.setEncoder(stdoutAppender.getEncoder());
        fileAppender.setContext(stdoutAppender.getContext());
        fileAppender.start();
        rootLogger.addAppender(fileAppender);
    }

    public static final String CONSOLE_APPENDER_NAME = "STDOUT";
    public static final String TEST_ROOT_FILE_APPENDER_NAME = "testRootFileAppender";
}
