package org.janelia.render.service.model;

import javax.ws.rs.core.Response;

/**
 * This exception is thrown when a resource cannot be found.
 *
 * @author Eric Trautman
 */
public class ObjectNotFoundException
        extends ServiceException {

    public ObjectNotFoundException(String message) {
        super(message, Response.Status.NOT_FOUND);
    }
}