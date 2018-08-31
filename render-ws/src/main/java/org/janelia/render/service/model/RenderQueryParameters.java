package org.janelia.render.service.model;

import java.io.Serializable;

import javax.ws.rs.QueryParam;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.RenderDataService;

/**
 * Common query parameters for all render requests.
 *
 * @author Eric Trautman
 */
public class RenderQueryParameters
        implements Serializable {

    @QueryParam("binaryMask")
    private final Boolean binaryMask;

    @QueryParam("channels")
    private final String channels;

    @QueryParam("convertToGray")
    private final Boolean convertToGray;

    @QueryParam("excludeMask")
    private final Boolean excludeMask;

    @QueryParam("fillWithNoise")
    private final Boolean fillWithNoise;

    @QueryParam("filter")
    private final Boolean filter;

    @QueryParam("filterListName")
    private final String filterListName;

    @QueryParam("minIntensity")
    private final Double minIntensity;

    @QueryParam("maxIntensity")
    private final Double maxIntensity;

    @QueryParam("scale")
    private Double scale;

    public RenderQueryParameters() {
        this(null);
    }

    public RenderQueryParameters(final Double scale) {
        this(null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             null,
             scale);
    }

    private RenderQueryParameters(final Boolean binaryMask,
                                  final String channels,
                                  final Boolean convertToGray,
                                  final Boolean excludeMask,
                                  final Boolean fillWithNoise,
                                  final Boolean filter,
                                  final String filterListName,
                                  final Double minIntensity,
                                  final Double maxIntensity,
                                  final Double scale) {
        this.binaryMask = binaryMask;
        this.channels = channels;
        this.convertToGray = convertToGray;
        this.excludeMask = excludeMask;
        this.fillWithNoise = fillWithNoise;
        this.filter = filter;
        this.filterListName = filterListName;
        this.minIntensity = minIntensity;
        this.maxIntensity = maxIntensity;
        this.scale = scale;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public Boolean getBinaryMask() {
        return binaryMask;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public String getChannels() {
        return channels;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public Boolean getConvertToGray() {
        return convertToGray;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public Boolean getExcludeMask() {
        return excludeMask;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public Boolean getFillWithNoise() {
        return fillWithNoise;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public Boolean getFilter() {
        return filter;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public String getFilterListName() {
        return filterListName;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public Double getMinIntensity() {
        return minIntensity;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public Double getMaxIntensity() {
        return maxIntensity;
    }

    @SuppressWarnings("unused") // getter required for Swagger to detect parameter
    public Double getScale() {
        return scale;
    }

    public void setDefaultScale(final Double defaultScale) {
        if (this.scale == null) {
            this.scale = defaultScale;
        }
    }

    public void applyQueryAndDefaultParameters(final RenderParameters renderParameters,
                                               final StackMetaData stackMetaData,
                                               final RenderDataService renderDataService) {

        // apply "simple" parameters
        renderParameters.setBinaryMask(binaryMask);
        renderParameters.setChannels(channels);
        renderParameters.setConvertToGray(convertToGray);
        renderParameters.setExcludeMask(excludeMask);
        renderParameters.setFillWithNoise(fillWithNoise);
        renderParameters.setDoFilter(filter);
        renderParameters.setMinIntensity(minIntensity);
        renderParameters.setMaxIntensity(maxIntensity);

        // retrieve named filter spec list and apply
        renderDataService.setFilterSpecs(filterListName, renderParameters);

        // apply stack default parameters
        if (stackMetaData != null) {

            renderParameters.setMipmapPathBuilder(stackMetaData.getCurrentMipmapPathBuilder());

            // if channels have not been explicitly requested ...
            if (channels == null) {
                final String defaultChannel = stackMetaData.getCurrentDefaultChannel();
                // ... and this stack has a default channel, "select" the stack default channel for rendering
                if (defaultChannel != null) {
                    renderParameters.setChannels(defaultChannel);
                }
            }

        }

        // Override existing scale with query scale if it is specified.
        //
        // This is necessary because I screwed up some of the original service paths
        // by specifying scale as a path parameter instead of a query parameter.
        //
        // If a request to one of those APIs also includes a scale query parameter
        // (e.g. .../box/{x},{y},{width},{height},{scaleA}/render-parameters?scale={scaleB}),
        // then the query parameter "wins".
        if (scale != null) {
            renderParameters.setScale(scale);
        }
    }

}