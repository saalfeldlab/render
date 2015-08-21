package org.janelia.render.service.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

import java.util.ArrayList;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;

/**
 * Instance of {@link JacksonJaxbJsonProvider} that uses common configured {@link JsonUtils#MAPPER}.
 *
 * @author Eric Trautman
 */
@Provider
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfiguredJsonProvider extends JacksonJaxbJsonProvider {

    public ConfiguredJsonProvider () {
        super();
        // only set mapper here if you wish to use the same configured mapper for all requests
//        setMapper(JsonUtils.MAPPER);
    }

    @Override
    protected ObjectMapper _locateMapperViaProvider(final Class<?> type,
                                                    final MediaType mediaType) {

        final ObjectMapper mapper;

        // for collections and lists, use fast mapper which skips pretty printing
        if ((type == ResolvedTileSpecCollection.class) || (type == ArrayList.class)) {
            mapper = JsonUtils.FAST_MAPPER;
        } else {
            mapper = JsonUtils.MAPPER;
        }

        return mapper;
    }

}
