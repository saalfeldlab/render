package org.janelia.render.service.model;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * This exception class serves as the base class for all web service exceptions.
 *
 * @author Eric Trautman 
 */
public class ServiceException
        extends WebApplicationException {

    private String message;

//    public ServiceException(String message) {
//        this(message, Response.Status.INTERNAL_SERVER_ERROR, null);
//    }
//
    public ServiceException(String message,
                            Response.Status status) {
        this(message, status, null);
    }

    public ServiceException(String message,
                            Throwable cause) {
        this(message, Response.Status.INTERNAL_SERVER_ERROR, cause);
    }

    public ServiceException(String message,
                            Response.Status status,
                            Throwable cause) {
        super(cause,
              getResponse(message, status));
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public static Response getResponse(String message,
                                       Response.Status status) {
        Response.ResponseBuilder builder =
                Response.status(status);
        builder.entity(message).type(MediaType.TEXT_PLAIN_TYPE);
        return builder.build();
    }

}