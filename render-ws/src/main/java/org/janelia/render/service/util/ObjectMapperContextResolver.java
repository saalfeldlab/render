package org.janelia.render.service.util;

import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RESTEasy can pick either {@link ConfiguredJsonProvider} or the library's own default JSON provider
 * to handle a response.  Both providers now match "application/json" equally well, so the pick
 * is not deterministic.
 * <p>
 * This class removes the need to control that pick.  Both providers ask a {@code ContextResolver} for
 * the {@code ObjectMapper} before they serialize a response.  This class is that {@code ContextResolver}.  It
 * always returns the correct field-visibility mapper, no matter which provider RESTEasy chose.
 */
@Provider
@Produces(MediaType.APPLICATION_JSON)
public class ObjectMapperContextResolver implements ContextResolver<ObjectMapper> {

    @Override
    public ObjectMapper getContext(final Class<?> type) {
        return ConfiguredJsonProvider.getMapperForType(type);
    }

}
