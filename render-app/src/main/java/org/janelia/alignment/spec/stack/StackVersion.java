package org.janelia.alignment.spec.stack;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * Details about a specific version of stack.
 *
 * @author Eric Trautman
 */
public class StackVersion
        implements Serializable {

    private final Date createTimestamp;
    private final String versionNotes;

    private final Integer cycleNumber;
    private final Integer cycleStepNumber;

    private Double stackResolutionX;
    private Double stackResolutionY;
    private Double stackResolutionZ;

    private String materializedBoxRootPath;
    private MipmapPathBuilder mipmapPathBuilder;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private StackVersion() {
        this(null, null, null, null, null, null, null, null, null);
    }

    public StackVersion(final Date createTimestamp,
                        final String versionNotes,
                        final Integer cycleNumber,
                        final Integer cycleStepNumber,
                        final Double stackResolutionX,
                        final Double stackResolutionY,
                        final Double stackResolutionZ,
                        final String materializedBoxRootPath,
                        final MipmapPathBuilder mipmapPathBuilder) {
        this.createTimestamp = createTimestamp;
        this.versionNotes = versionNotes;
        this.cycleNumber = cycleNumber;
        this.cycleStepNumber = cycleStepNumber;
        this.stackResolutionX = stackResolutionX;
        this.stackResolutionY = stackResolutionY;
        this.stackResolutionZ = stackResolutionZ;
        this.materializedBoxRootPath = materializedBoxRootPath;
        this.mipmapPathBuilder = mipmapPathBuilder;
    }

    public Date getCreateTimestamp() {
        return createTimestamp;
    }

    public String getVersionNotes() {
        return versionNotes;
    }

    public Integer getCycleNumber() {
        return cycleNumber;
    }

    public Integer getCycleStepNumber() {
        return cycleStepNumber;
    }

    public Double getStackResolutionX() {
        return stackResolutionX;
    }

    public Double getStackResolutionY() {
        return stackResolutionY;
    }

    public Double getStackResolutionZ() {
        return stackResolutionZ;
    }

    public List<Double> getStackResolutionValues() {
        final List<Double> resolutionValues = new ArrayList<>();
        resolutionValues.add(stackResolutionX);
        resolutionValues.add(stackResolutionY);
        resolutionValues.add(stackResolutionZ);
        return resolutionValues;
    }

    public void setStackResolutionValues(final List<Double> resolutionValues) {
        if (resolutionValues.size() > 0) {
            stackResolutionX = resolutionValues.get(0);
            if (resolutionValues.size() > 1) {
                stackResolutionY = resolutionValues.get(1);
                if (resolutionValues.size() > 2) {
                    stackResolutionZ = resolutionValues.get(2);
                }
            }
        }
    }

    public String getMaterializedBoxRootPath() {
        return materializedBoxRootPath;
    }

    public void setMaterializedBoxRootPath(final String materializedBoxRootPath) {
        String trimmedPath = null;
        if (materializedBoxRootPath != null) {
            trimmedPath = materializedBoxRootPath.trim();
            if (trimmedPath.length() == 0) {
                trimmedPath = null;
            }
        }
        this.materializedBoxRootPath = trimmedPath;
    }

    public MipmapPathBuilder getMipmapPathBuilder() {
        return mipmapPathBuilder;
    }

    public void setMipmapPathBuilder(final MipmapPathBuilder mipmapPathBuilder)
            throws IllegalArgumentException {

        if (mipmapPathBuilder == null) {
            this.mipmapPathBuilder = null;
        } else {
            // reconstruct builder in case JSON de-serialization created "incomplete" instance
            this.mipmapPathBuilder = new MipmapPathBuilder(mipmapPathBuilder.getRootPath(),
                                                           mipmapPathBuilder.getNumberOfLevels(),
                                                           mipmapPathBuilder.getExtension());
        }
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<StackVersion> JSON_HELPER =
            new JsonUtils.Helper<>(StackVersion.class);
}
