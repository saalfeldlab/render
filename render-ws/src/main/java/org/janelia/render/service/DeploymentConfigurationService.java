package org.janelia.render.service;

import com.google.common.collect.Maps;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.janelia.alignment.filter.FilterFactory;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.service.util.RenderServerProperties;
import org.janelia.render.service.util.RenderServiceUtil;
import org.janelia.render.service.util.SharedImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * APIs for accessing Render service configuration.
 *
 * These APIs don't require database access and will work even if there is no available database connection.
 *
 * @author Eric Trautman
 */
@Path("/")
@Api()
public class DeploymentConfigurationService {

    private Map<String, String> versionInfo;
    private FilterFactory filterFactory;

    @SuppressWarnings("UnusedDeclaration")
    public DeploymentConfigurationService() {
        this.versionInfo = null;
        this.filterFactory = null;
    }

    @Path("v1/namedFilterSpecLists")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Service Configuration APIs",
            value = "Get configured rendering filters",
            produces = MediaType.APPLICATION_JSON)
    public FilterFactory getConfiguredNamedFilters() {
        return getFilterFactory();
    }

    @Path("v1/serverProperties")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Service Configuration APIs",
            value = "The configured properties for this render server instance")
    public RenderServerProperties getServerProperties() {
        return RenderServerProperties.getProperties();
    }

    @Path("v1/versionInfo")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Service Configuration APIs",
            value = "The build version information for deployed services")
    public Map<String, String> getVersionInfo() {

        if (versionInfo == null) {
            // load version info created by maven build process if it is available
            try {
                final InputStream infoStream = getClass().getClassLoader().getResourceAsStream("git.properties");
                if (infoStream != null) {
                    final Properties p = new Properties();
                    p.load(infoStream);

                    // add commit URL to make it easier to cut-and-paste into a browser
                    final String remoteOriginUrl = p.getProperty("git.remote.origin.url");
                    final String commitId = p.getProperty("git.commit.id");
                    if ((remoteOriginUrl != null) && (commitId != null)) {
                        p.setProperty("git.commit.url", String.format("%s/commit/%s", remoteOriginUrl, commitId));
                    }

                    versionInfo = Maps.fromProperties(p);

                    LOG.info("getVersionInfo: loaded version info");
                }
            } catch (final Throwable t) {
                LOG.warn("getVersionInfo: failed to load version info", t);
            }
        }

        return versionInfo;
    }

    @Path("v1/imageProcessorCache/allEntries")
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Service Configuration APIs",
            value = "Discards all cached images",
            produces = MediaType.APPLICATION_JSON)
    public Response invalidateImageProcessorCache() {
        Response response = null;
        try {
            final ImageProcessorCache sharedCache = SharedImageProcessorCache.getInstance();
            LOG.info("invalidateImageProcessorCache: entry, invalidating {} elements, current stats are: {}",
                     sharedCache.size(), sharedCache.getStats());
            sharedCache.invalidateAll();

            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    private FilterFactory getFilterFactory() {
        if (this.filterFactory == null) {
            // lazy-load factory configuration from JSON file
            this.filterFactory = FilterFactory.loadConfiguredInstance();
        }
        return this.filterFactory;
    }

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentConfigurationService.class);
}
