package org.janelia.render.client.parameter;

import java.io.Serializable;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

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

}