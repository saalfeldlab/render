package org.janelia.render.service;

import org.janelia.alignment.RenderParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data access object for render parameters.
 *
 * @author Eric Trautman
 */
public class RenderParametersDao {

    private File baseDirectory;

    public RenderParametersDao(File baseDirectory) {
        try {
            this.baseDirectory = baseDirectory.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot derive canonical path for render parameters base directory " +
                                               baseDirectory.getAbsolutePath(),
                                               e);
        }

        if (! this.baseDirectory.exists()) {
            throw new IllegalArgumentException("render parameters base directory " + baseDirectory.getAbsolutePath() +
                                               " does not exist");
        }
    }

    public RenderParameters getParameters(String projectId,
                                          String stackId,
                                          Integer x,
                                          Integer y,
                                          Integer width,
                                          Integer height)
            throws IllegalArgumentException {

        validateIdName("projectId", projectId);
        validateIdName("stackId", stackId);
        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("width", width);
        validateRequiredParameter("height", height);

        RenderParameters parameters = null;

        final File parameterFile = getParameterFile(projectId, stackId, x, y, width, height);
        BufferedReader reader = null;
        try {

            reader = new BufferedReader(new FileReader(parameterFile));
            parameters = RenderParameters.DEFAULT_GSON.fromJson(reader, RenderParameters.class);

        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("parameter file " + parameterFile.getAbsolutePath() + " does not exist",
                                               e);
        } catch (Throwable t) {
            throw new IllegalArgumentException("failed to parse parameter file " + parameterFile.getAbsolutePath(), t);

        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    LOG.warn("failed to close " + parameterFile.getAbsolutePath(), e);
                }
            }
        }

        return parameters;
    }

    private void validateIdName(String context,
                                String idName)
            throws IllegalArgumentException {

        validateRequiredParameter(context, idName);

        final Matcher m = VALID_ID_NAME.matcher(idName);
        if (! m.matches()) {
            throw new IllegalArgumentException("invalid " + context + " name '" + idName + "' specified");
        }
    }

    private void validateRequiredParameter(String context,
                                           Object value)
            throws IllegalArgumentException {

        if (value == null) {
            throw new IllegalArgumentException(context + " value must be specified");
        }
    }

    private File getParameterFile(String projectId,
                                  String stackId,
                                  Integer x,
                                  Integer y,
                                  Integer width,
                                  Integer height)
            throws IllegalArgumentException {

        final String relativePath = projectId + '/' + stackId + '/' + x + '/' + y + '/' + width + '/' + height +'/';
        final String name = projectId + '_' + stackId + '_' + x + '_' + y + '_' + width + '_' + height + ".json";
        File file = new File(baseDirectory, relativePath + name);
        try {
            file = file.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot derive canonical path for " + file.getAbsolutePath(), e);
        }
        return file;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderService.class);

    private static final Pattern VALID_ID_NAME = Pattern.compile("[A-Za-z0-9\\-]++");
}
