package org.janelia.render.client.parameter;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base parameters for all command line tools.
 *
 * @author Eric Trautman
 */
@Parameters
public class CommandLineParameters implements Serializable {

    @Parameter(
            names = "--help",
            description = "Display this note",
            help = true)
    public transient boolean help;

    private transient JCommander jCommander;

    public CommandLineParameters() {
        this.help = false;
        this.jCommander = null;
    }

    public void parse(final String[] args) throws IllegalArgumentException {
        parse(args, this.getClass().getEnclosingClass(), true);
    }

    public void parse(final String[] args,
                      final Class programClass,
                      final boolean exitOnHelpOrFailure) throws IllegalArgumentException {

        jCommander = new JCommander(this);
        jCommander.setProgramName("java -cp <render-module>-standalone.jar " + programClass.getName());

        boolean parseFailed = true;
        try {
            jCommander.parse(args);
            parseFailed = false;
        } catch (final ParameterException pe) {
            JCommander.getConsole().println("\nERROR: failed to parse command line arguments\n\n" + pe.getMessage());
        } catch (final Throwable t) {
            LOG.error("failed to parse command line arguments", t);
        }

        if (help || parseFailed) {
            JCommander.getConsole().println("");
            jCommander.usage();
            if (exitOnHelpOrFailure) {
                System.exit(1);
            }
        }
    }

    /**
     * @return string representation of these parameters.
     */
    @Override
    public String toString() {
        try {
            return JsonUtils.MAPPER.writeValueAsString(this);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Helper (no pun intended) for testing parameter parsing.
     *
     * @param  parameters  parameters instance to test.
     */
    public static void parseHelp(final CommandLineParameters parameters) {
        parameters.parse(new String[] { "--help" },
                         parameters.getClass().getEnclosingClass(),
                         false);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CommandLineParameters.class);

}