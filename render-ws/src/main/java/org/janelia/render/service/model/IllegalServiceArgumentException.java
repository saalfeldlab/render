package org.janelia.render.service.model;

import javax.ws.rs.core.Response;

/**
 * This exception is thrown when a request is missing required information.
 *
 * @author Eric Trautman
 */
public class IllegalServiceArgumentException
        extends ServiceException {

    public IllegalServiceArgumentException(String message) {
        super(message, Response.Status.BAD_REQUEST);
    }

    public IllegalServiceArgumentException(String message,
                                           Throwable cause) {
        super(message, Response.Status.BAD_REQUEST, cause);
    }
}