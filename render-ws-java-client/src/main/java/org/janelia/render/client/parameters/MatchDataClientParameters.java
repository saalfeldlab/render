package org.janelia.render.client.parameters;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

/**
 * Base parameters for all match web service clients.
 *
 * @author Eric Trautman
 */
@Parameters
public class MatchDataClientParameters implements Serializable {

    @Parameter(
            names = "--baseDataUrl",
            description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
            required = true,
            order = 1)
    public String baseDataUrl;

    @Parameter(
            names = "--owner",
            description = "Match collection owner",
            required = true,
            order = 2)
    public String owner;

    @Parameter(
            names = "--collection",
            description = "Match collection name",
            required = true,
            order = 3)
    public String collection;

    public MatchDataClientParameters() {
        this.baseDataUrl = null;
        this.owner = null;
        this.collection = null;
    }

}