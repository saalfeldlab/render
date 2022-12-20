package org.janelia.render.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.ConsensusSetData;
import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.models.PointMatch;

/**
 * Java client for RANSAC filtering a list of point matches.
 *
 * The candidateFile is expected to be a JSON array of {@link CanvasMatches} elements.
 *
 * The filtered results are written to
 * <pre>
 *     [outputDirectory]/[candidateFile basename]_filtered.[extension]
 *</pre>
 * in the same format (a JSON array of {@link CanvasMatches} elements).
 * For example, the options --candidateFile /a/candidates.json --outputDirectory /b
 * would have results written to /b/candidates_filtered.json
 *
 * Note that if a matchFilter of {@link MatchFilter.FilterType#CONSENSUS_SETS} is specified,
 * the pId and qId values for pairs with multiple sets will be modified to include the set index.
 * For example, { ..., "pId": "a", "qId": "b", ... } might become
 * { ..., "pId": "a_set_0", "qId": "b_set_0", ... }, { ..., "pId": "a_set_1", "qId": "b_set_1", ... }.
 *
 * @author Eric Trautman
 */
public class RansacFilterClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchDerivationParameters matchDerivation = new MatchDerivationParameters();

        @Parameter(
                names = "--renderScale",
                description = "Render scale for match points being filtered",
                order = 1)
        public Double renderScale = 1.0;

        @Parameter(
                names = "--candidateFile",
                description = "JSON file containing array of pairs with matches to be filtered (.json, .gz, or .zip)",
                required = true,
                order = 1)
        public String candidateFile;

        @Parameter(
                names = "--outputDirectory",
                description = "Path of directory where filtered match result file(s) should be written",
                required = true,
                order = 1)
        public String outputDirectory;

    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final RansacFilterClient client = new RansacFilterClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    RansacFilterClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run() throws Exception {

        final MatchFilter matchFilter = new MatchFilter(parameters.matchDerivation,
                                                        parameters.renderScale);

        final List<CanvasMatches> inlierCanvasMatchesLists = new ArrayList<>();

        for (final CanvasMatches pair : loadMatchData(parameters.candidateFile)) {

            final List<PointMatch> candidates = new ArrayList<>(
                    CanvasMatchResult.convertMatchesToPointMatchList(pair.getMatches()));

            final List<List<PointMatch>> inliersLists = matchFilter.filterConsensusMatches(candidates);

            final int numberOfConsensusSets = inliersLists.size();

            for (int i = 0; i < numberOfConsensusSets; i++) {
                final CanvasMatches filteredCanvasMatches;
                if (numberOfConsensusSets > 1) {
                    final String setSuffix = String.format("_set_%03d", i);
                    filteredCanvasMatches =
                        new CanvasMatches(pair.getpGroupId(),
                                          pair.getpId() + setSuffix,
                                          pair.getqGroupId(),
                                          pair.getqId() + setSuffix,
                                          CanvasMatchResult.convertPointMatchListToMatches(inliersLists.get(i),
                                                                                           1.0));
                    filteredCanvasMatches.setConsensusSetData(new ConsensusSetData(i, pair.getpId(), pair.getqId()));
                } else {
                    filteredCanvasMatches =
                            new CanvasMatches(pair.getpGroupId(),
                                              pair.getpId(),
                                              pair.getqGroupId(),
                                              pair.getqId(),
                                              CanvasMatchResult.convertPointMatchListToMatches(inliersLists.get(i),
                                                                                               1.0));
                }
                inlierCanvasMatchesLists.add(filteredCanvasMatches);
            }

        }

        final String[] sourceFileNameElements = splitFileName(parameters.candidateFile);
        final String baseName = sourceFileNameElements[0] + "_filtered";
        final String extension = sourceFileNameElements[1];
        final Path outputFilePath = Paths.get(parameters.outputDirectory, baseName + extension).toAbsolutePath();
        saveMatchData(inlierCanvasMatchesLists, outputFilePath.toString());

    }

    private List<CanvasMatches> loadMatchData(final String dataFile)
            throws IOException, IllegalArgumentException {

        final List<CanvasMatches> matchPairCandidates;

        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();

        LOG.info("load: entry, path={}", path);

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            matchPairCandidates = CanvasMatches.fromJsonArray(reader);
        }

        LOG.info("load: exit, loaded {} pairs", matchPairCandidates.size());

        return matchPairCandidates;
    }

    private void saveMatchData(final List<CanvasMatches> filteredData,
                               final String outputFileName)
            throws IOException {
        FileUtil.saveJsonFile(outputFileName, filteredData);
        LOG.info("saveMatchData: exit, saved {} filter pairs to {}", filteredData.size() , outputFileName);
    }

    private String[] splitFileName(final String fullPathName) {
        final int extensionIndex;
        String baseName = FilenameUtils.getBaseName(fullPathName);
        if (fullPathName.endsWith(".gz") || fullPathName.endsWith(".zip")) {
            extensionIndex = FilenameUtils.indexOfExtension(baseName);
            baseName = fullPathName.substring(0, extensionIndex);
        } else {
            extensionIndex = FilenameUtils.indexOfExtension(fullPathName);
        }
        return new String[] { baseName, fullPathName.substring(extensionIndex)};
    }

    private static final Logger LOG = LoggerFactory.getLogger(RansacFilterClient.class);
}
