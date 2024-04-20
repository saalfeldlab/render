package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;

import java.io.Serializable;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.loader.DynamicMaskLoader;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.newsolver.setup.TargetStackParameters;

// TODO: move/merge this into larger copy stack parameters class when the copy clients get updated

/**
 * Parameters for adding or updating masks.
 *
 * @author Eric Trautman
 */
@Parameters
public class MaskHackParameters
        implements Serializable {

    @ParametersDelegate
    public TargetStackParameters targetStack;

    @Parameter(
            names = "--dynamicMaskValue",
            description = "Value (without mask:// prefix) for all masks, e.g. " +
                          "outside-box?minX=0&minY=10&maxX=2000&maxY=1748&width=2000&height=1748")
    public String dynamicMaskValue;

    public MaskHackParameters() {
    }

    public void validate()
            throws IllegalArgumentException {

        if (dynamicMaskValue == null) {
            throw new IllegalArgumentException("must specify --dynamicMaskValue");
        } else {
            DynamicMaskLoader.parseUrl(getDynamicMaskUrl()); // throws exception if mask value is invalid
        }
    }

    public String getDynamicMaskUrl() {
        return dynamicMaskValue == null ? null : "mask://" + dynamicMaskValue;
    }

    public MaskHackParameters buildPipelineClone(final StackId sourceStackId) {
        final MaskHackParameters clone = this.clone();
        clone.targetStack.setValuesFromPipeline(sourceStackId, "_masked");
        return clone;
    }

    /** (Slowly) creates a clone of this setup by serializing it to and from JSON. */
    @SuppressWarnings("MethodDoesntCallSuperMethod")
    public MaskHackParameters clone() {
        final String json = JSON_HELPER.toJson(this);
        return JSON_HELPER.fromJson(json);
    }

    private static final JsonUtils.Helper<MaskHackParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MaskHackParameters.class);

}