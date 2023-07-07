package org.janelia.alignment.match.parameters;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.util.FileUtil;

/**
 * Parameters for a full match run that includes tile pair derivation
 * along with all stages in a multi-stage (pass) process.
 *
 * @author Eric Trautman
 */
public class MatchRunParameters
        implements Serializable {

    private final String runName;
    private final MatchCommonParameters matchCommon;
    private final TilePairDerivationParameters tilePairDerivationParameters;
    private final List<MatchStageParameters> matchStageParametersList;

    @SuppressWarnings("unused")
    public MatchRunParameters() {
        this(null, new MatchCommonParameters(), new TilePairDerivationParameters(), new ArrayList<>());
    }

    public MatchRunParameters(final String runName,
                              final MatchCommonParameters matchCommon,
                              final TilePairDerivationParameters tilePairDerivationParameters,
                              final List<MatchStageParameters> matchStageParametersList) {
        this.runName = runName;
        this.matchCommon = matchCommon;
        this.tilePairDerivationParameters = tilePairDerivationParameters;
        this.matchStageParametersList = matchStageParametersList;
    }

    public String getRunName() {
        return runName;
    }

    public MatchCommonParameters getMatchCommon() {
        return matchCommon;
    }

    public TilePairDerivationParameters getTilePairDerivationParameters() {
        return tilePairDerivationParameters;
    }

    public List<MatchStageParameters> getMatchStageParametersList() {
        return matchStageParametersList;
    }

    public void validateAndSetDefaults() throws IllegalArgumentException {
        for (final MatchStageParameters matchStageParameters : matchStageParametersList) {
            matchStageParameters.validateAndSetDefaults();
        }
    }

    /*
     * @return JSON representation of these parameters.
     */
    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static List<MatchRunParameters> fromJsonArray(final Reader json) {
        return JSON_HELPER.fromJsonArray(json);
    }

    public static List<MatchRunParameters> fromJsonArrayFile(final String dataFile)
            throws IOException {
        final List<MatchRunParameters> runParametersList;
        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            runParametersList = fromJsonArray(reader);
        }
        return runParametersList;
    }

    private static final JsonUtils.Helper<MatchRunParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MatchRunParameters.class);
}