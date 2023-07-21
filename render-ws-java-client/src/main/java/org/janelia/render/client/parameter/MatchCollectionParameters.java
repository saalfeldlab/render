package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import org.janelia.render.client.RenderDataClient;

/**
 * Parameters for identifying a match collection.
 *
 * @author Eric Trautman
 */
public class MatchCollectionParameters
        implements Serializable {

    @Parameter(
            names = "--matchOwner",
            description = "Match collection owner (default is to use stack owner)")
    public String matchOwner;

    @Parameter(
            names = "--matchCollection",
            description = "Match collection name")
    public String matchCollection;

    public RenderDataClient getMatchDataClient(final String baseDataUrl, final String defaultOwner) {
        RenderDataClient client = null;
        if (matchCollection != null) {
            final String owner = matchOwner == null ? defaultOwner : matchOwner;
            client = new RenderDataClient(baseDataUrl, owner, matchCollection);
        }
        return client;
    }
}
