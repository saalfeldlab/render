package org.janelia.render.service;

import org.janelia.alignment.RenderParameters;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data access object for render parameters.
 *
 * This is just a "stub" to load specs directly from the file system
 * until we figure out the long term data architecture.
 *
 * @author Eric Trautman
 */
public class RenderParametersDao {

    private File baseDirectory;

    /**
     * @param  baseDirectory  root directory for all specs.
     */
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

    /**
     * @return a render parameters object for the specified stack.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public RenderParameters getParameters(String projectId,
                                          String stackId,
                                          Double x,
                                          Double y,
                                          Integer z,
                                          Integer width,
                                          Integer height,
                                          Integer zoomLevel)
            throws IllegalArgumentException {

        validateIdName("projectId", projectId);
        validateIdName("stackId", stackId);
        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);
        validateRequiredParameter("width", width);
        validateRequiredParameter("height", height);
        validateRequiredParameter("zoomLevel", zoomLevel);

        final File tileSpecFile = getTileSpecFile(projectId, stackId, z);
        final URI tileSpecUri = tileSpecFile.toURI();
        return new RenderParameters(tileSpecUri.toString(),
                                    x,
                                    y,
                                    width,
                                    height,
                                    zoomLevel);
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

    private File getTileSpecFile(String projectId,
                                 String stackId,
                                 Integer z)
            throws IllegalArgumentException {

        final String relativePath = projectId + '/' + stackId + '/';
        final String name = projectId + '_' + stackId + '_' + z + ".json";
        File file = new File(baseDirectory, relativePath + name);
        try {
            file = file.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot derive canonical path for " + file.getAbsolutePath(), e);
        }
        return file;
    }

    private static final Pattern VALID_ID_NAME = Pattern.compile("[A-Za-z0-9\\-]++");
}
