package org.janelia.render.client.spark;

import com.google.common.io.CharStreams;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.spark.api.java.JavaSparkContext;
import org.slf4j.LoggerFactory;

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

        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final LoggerConfig rootConfig = ctx.getConfiguration().getRootLogger();
        final String conversionPattern = "%d{ISO8601} [%t] [%X{context}] %-5p [%c] %m%n";

        for (final Appender appender : rootConfig.getAppenders().values()) {
            if (appender instanceof final ConsoleAppender consoleAppender &&
                consoleAppender.getLayout() instanceof final PatternLayout patternLayout &&
                ! conversionPattern.equals(patternLayout.getConversionPattern())) {

                final ConsoleAppender newAppender =
                        ConsoleAppender.newBuilder()
                                .setName(consoleAppender.getName())
                                .setTarget(consoleAppender.getTarget())
                                .setLayout(PatternLayout.newBuilder().withPattern(conversionPattern).build())
                                .build();
                newAppender.start();
                rootConfig.removeAppender(consoleAppender.getName());
                rootConfig.addAppender(newAppender, null, null);
                ctx.updateLoggers();
            }
        }

        ThreadContext.put("context", context);

        Configurator.setLevel(rootLoggerName, Level.DEBUG);
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
            url = new URI(apiUrl + "/api/v1/applications/" + appId + "/executors").toURL();

            try (final BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()))) {
                json = CharStreams.toString(in);
            } catch (final Throwable t) {
                json = getErrorJson("failed to retrieve executors data", t);
            }

        } catch (final URISyntaxException | MalformedURLException e) {
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
}
