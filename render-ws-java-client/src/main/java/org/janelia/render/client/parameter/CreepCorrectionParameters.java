package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Parameters for correcting multi-SEM image creep.
 */
@Parameters
public class CreepCorrectionParameters
        implements Serializable {

    public enum MatchCorrectionType {
        OVERWRITE_SOURCE,
        OVERWRITE_SOURCE_AND_RENAME_AS_TARGET,
        WRITE_TO_TARGET,
        SKIP
    }

    @Parameter(
            names = "--creepTargetStackSuffix",
            description = "Target stack name is the the source stack name with this suffix appended")
    public String targetStackSuffix = "_crc";

    @Parameter(
            names = "--creepMinZ",
            description = "Minimum Z value for layers that need creep correction")
    public Double minZ;

    @Parameter(
            names = "--creepMaxZ",
            description = "Maximum Z value for layers that need creep correction")
    public Double maxZ;

    @Parameter(
            names = "--matchCorrectionType",
            description = "Type of match correction")
    public MatchCorrectionType matchCorrectionType = MatchCorrectionType.WRITE_TO_TARGET;

    @Parameter(
            names = "--parameterCsvDir",
            description = "Directory where a creep-correction-param.<sourceStackId>.csv file " +
                          "with per-mFOV parameters, stretch estimates, and validation results " +
                          "will be written for each source stack")
    public String parameterCsvDir;

    public CreepCorrectionParameters() {
    }

    public boolean correctMatches() {
        return matchCorrectionType != MatchCorrectionType.SKIP;
    }

    public boolean overwriteMatchData() {
        return matchCorrectionType == MatchCorrectionType.OVERWRITE_SOURCE;
    }

    public boolean overwriteMatchDataAndRenameAsTarget() {
        return matchCorrectionType == MatchCorrectionType.OVERWRITE_SOURCE_AND_RENAME_AS_TARGET;
    }

    public boolean writeMatchDataToTargetCollection() {
        return matchCorrectionType == MatchCorrectionType.WRITE_TO_TARGET;
    }

    public void validate()
            throws IllegalArgumentException {

        if ((targetStackSuffix == null) || (targetStackSuffix.trim().isEmpty())) {
            throw new IllegalArgumentException("--creepTargetStackSuffix must be defined");
        }

        if (parameterCsvDir != null) {
            final File csvDir = new File(parameterCsvDir);
            if (! csvDir.isDirectory()) {
                throw new IllegalArgumentException("--parameterCsvDir " + parameterCsvDir + " is not a valid directory");
            }
        }
    }

    @Override
    public String toString() {
        return "{targetStackSuffix='" + targetStackSuffix + '\'' +
               ", minZ=" + minZ +
               ", maxZ=" + maxZ +
               ", matchCorrectionType=" + matchCorrectionType +
               ", parameterCsvDir='" + parameterCsvDir + '\'' +
               '}';
    }

    public String getTargetStack(final String sourceStack) {
        return sourceStack + targetStackSuffix;
    }


    public List<Double> buildCorrectedZValues(final List<Double> allZValues) {
        final List<Double> correctedZValues = new ArrayList<>();
        for (final Double z : allZValues) {
            if ( (minZ == null || (z >= minZ)) && (maxZ == null || (z <= maxZ)) ) {
                correctedZValues.add(z);
            }
        }
        return correctedZValues;
    }

    public List<Double> buildUncorrectedZValues(final List<Double> allZValues) {
        final List<Double> uncorrectedZValues = new ArrayList<>();
        for (final Double z : allZValues) {
            if ( (minZ != null && (z < minZ)) || (maxZ != null && (z > maxZ)) ) {
                uncorrectedZValues.add(z);
            }
        }
        return uncorrectedZValues;
    }
}