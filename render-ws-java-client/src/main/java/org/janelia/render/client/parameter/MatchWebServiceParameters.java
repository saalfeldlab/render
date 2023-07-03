package org.janelia.render.client.parameter;

import java.io.Serializable;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.annotation.JsonIgnore;

import org.janelia.render.client.RenderDataClient;

/**
 * Parameters for match web service clients.
 *
 * @author Eric Trautman
 */
@Parameters
public class MatchWebServiceParameters implements Serializable {

    @Parameter(
            names = "--baseDataUrl",
            description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
            required = true)
    public String baseDataUrl;

    @Parameter(
            names = "--owner",
            description = "Match collection owner",
            required = true)
    public String owner;

    @Parameter(
            names = "--collection",
            description = "Match collection name",
            required = true)
    public String collection;

    public MatchWebServiceParameters() {
        this(null, null, null);
    }

    public MatchWebServiceParameters(final String baseDataUrl,
                                     final String owner,
                                     final String collection) {
        this.baseDataUrl = baseDataUrl;
        this.owner = owner;
        this.collection = collection;
    }

    /** Local data client instance that is lazy loaded since client cannot be serialized. */
    private transient RenderDataClient dataClient;

    @JsonIgnore
    public RenderDataClient getDataClient() {
        if (dataClient == null) {
            buildDataClient();
        }
        return dataClient;
    }

    private synchronized void buildDataClient() {
        if (dataClient == null) {
            dataClient = new RenderDataClient(baseDataUrl, owner, collection);
        }
    }

}