package org.janelia.render.client.spark;

import java.util.Enumeration;

import org.apache.log4j.Appender;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.apache.log4j.PatternLayout;

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

        for (final Enumeration e = LogManager.getRootLogger().getAllAppenders(); e.hasMoreElements(); ) {
            final Appender a = (Appender) e.nextElement();
            if (a instanceof ConsoleAppender) {
                final Layout layout = a.getLayout();
                if (layout instanceof PatternLayout) {
                    final PatternLayout patternLayout = (PatternLayout) layout;
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

}
