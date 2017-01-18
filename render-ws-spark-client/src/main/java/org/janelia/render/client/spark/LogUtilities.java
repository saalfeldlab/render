package org.janelia.render.client.spark;

import com.google.common.io.CharStreams;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.MalformedURLException;
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
    public static String getExecutorsApiJson(final String appId, final String apiUrl){
    	// TODO: find more robust way to determine execution context

        String json;

        try{
        	final URL url = new URL(apiUrl+ "/api/v1/applications/" + appId + "/executors");
        	final BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
        	json = CharStreams.toString(in);
        }
        catch (final MalformedURLException e){
        	  json = "[ {\n" +
                   "  \"error\": \"mallformed url \",\n" +
                   "  \"exception_message\": \"" + e.getMessage() + "\"\n" +
                   "} ]";
        }           
        catch (final Throwable t) {
            json = "[ {\n" +
                   "  \"error\": \"failed to retrieve executors data\",\n" +
                   "  \"exception_message\": \"" + t.getMessage() + "\"\n" +
                   "} ]";
        }

        return json;

    }
    public static String getExecutorsApiJson(final String appId) throws IOException {

        // TODO: find more robust way to determine execution context

        String json;

        final URL url = new URL("http://localhost:4040/api/v1/applications/" + appId + "/executors");
        try (final BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream())))  {
            json = CharStreams.toString(in);
        } catch (final Throwable t) {
            json = "[ {\n" +
                   "  \"error\": \"failed to retrieve executors data\",\n" +
                   "  \"exception_message\": \"" + t.getMessage() + "\"\n" +
                   "} ]";
        }

        return json;
    }

}
