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

    private final TilePairDerivationParameters tilePairDerivationParameters;
    private final List<MatchStageParameters> matchStageParametersList;

    @SuppressWarnings("unused")
    public MatchRunParameters() {
        this(new TilePairDerivationParameters(), new ArrayList<>());
    }

    public MatchRunParameters(final TilePairDerivationParameters tilePairDerivationParameters,
                              final List<MatchStageParameters> matchStageParametersList) {
        this.tilePairDerivationParameters = tilePairDerivationParameters;
        this.matchStageParametersList = matchStageParametersList;
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

    public static MatchRunParameters fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    public static MatchRunParameters fromJsonFile(final String dataFile)
            throws IOException {
        final MatchRunParameters matchRunParameters;
        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            matchRunParameters = fromJson(reader);
        }
        return matchRunParameters;
    }

    private static final JsonUtils.Helper<MatchRunParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MatchRunParameters.class);
}