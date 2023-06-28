package org.janelia.render.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;

import static org.janelia.render.client.TilePairClient.validateFileIsWritable;

/**
 * Java client for redistributing (batching) neighbor tile pair information from one set of files to another.
 *
 * @author Eric Trautman
 */
public class RedistributeTilePairsClient {

    public static class Parameters extends CommandLineParameters {

        @Parameter(
                names = "--fromJson",
                description = "JSON file where tile pairs are stored (.json, .gz, or .zip)",
                required = true,
                variableArity = true)
        public List<String> pairJson;

        @Parameter(
                names = "--toJson",
                description = "JSON file where redistributed tile pairs are to be stored (.json, .gz, or .zip)",
                required = true)
        public String toJson;

        @Parameter(
                names = "--maxPairsPerFile",
                description = "Maximum number of pairs to include in each output file.")
        public Integer maxPairsPerFile = 100000;

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                validateFileIsWritable(parameters.toJson);

                LOG.info("runClient: entry, parameters={}", parameters);

                final RedistributeTilePairsClient client = new RedistributeTilePairsClient(parameters);
                client.redistributePairs();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private String outputFileNamePrefix;
    private String outputFileNameSuffix;
    private int numberOfOutputFiles;

    RedistributeTilePairsClient(final Parameters parameters)
            throws IllegalArgumentException {

        this.parameters = parameters;

        this.outputFileNamePrefix = parameters.toJson;
        this.outputFileNameSuffix = "";
        final Pattern p = Pattern.compile("^(.*)\\.(json|gz|zip)$");
        final Matcher m = p.matcher(parameters.toJson);
        if (m.matches() && (m.groupCount() == 2)) {
            this.outputFileNamePrefix = m.group(1);
            this.outputFileNameSuffix = "." + m.group(2);
            if (this.outputFileNamePrefix.endsWith(".json")) {
                this.outputFileNamePrefix =
                        this.outputFileNamePrefix.substring(0, this.outputFileNamePrefix.length() - 5);
                this.outputFileNameSuffix = ".json" + this.outputFileNameSuffix;
            }
        }

        this.numberOfOutputFiles = 0;
    }

    private void redistributePairs()
            throws IllegalArgumentException, IOException {

        LOG.info("redistributePairs: entry");

        final Set<OrderedCanvasIdPair> neighborPairs = new TreeSet<>();

        int totalSavedPairCount = 0;
        RenderableCanvasIdPairs renderableCanvasIdPairs = null;
        for (final String pairJsonFileName : parameters.pairJson) {
            renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);
            neighborPairs.addAll(renderableCanvasIdPairs.getNeighborPairs());

            if (neighborPairs.size() > parameters.maxPairsPerFile) {
                final List<OrderedCanvasIdPair> neighborPairsList = new ArrayList<>(neighborPairs);
                int fromIndex = 0;
                for (; ; fromIndex += parameters.maxPairsPerFile) {
                    final int toIndex = fromIndex + parameters.maxPairsPerFile;
                    if (toIndex <= neighborPairs.size()) {
                        savePairs(neighborPairsList.subList(fromIndex, toIndex),
                                  renderableCanvasIdPairs.getRenderParametersUrlTemplate(),
                                  getOutputFileName());
                        numberOfOutputFiles++;
                        totalSavedPairCount += parameters.maxPairsPerFile;
                    } else {
                        break;
                    }
                }

                neighborPairs.clear();
                neighborPairs.addAll(neighborPairsList.subList(fromIndex, neighborPairsList.size()));

            }

        }

        if (neighborPairs.size() > 0) {
            final List<OrderedCanvasIdPair> neighborPairsList = new ArrayList<>(neighborPairs);
            final String outputFileName = numberOfOutputFiles == 0 ? parameters.toJson : getOutputFileName();
            savePairs(neighborPairsList, renderableCanvasIdPairs.getRenderParametersUrlTemplate(), outputFileName);
            totalSavedPairCount += neighborPairs.size();
        }

        LOG.info("redistributePairs: exit, saved {} total pairs", totalSavedPairCount);
    }

    private String getOutputFileName() {
        return String.format("%s_p%03d%s", outputFileNamePrefix, numberOfOutputFiles, outputFileNameSuffix);
    }

    private void savePairs(final List<OrderedCanvasIdPair> neighborPairs,
                           final String renderParametersUrlTemplate,
                           final String outputFileName)
            throws IOException {

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                new RenderableCanvasIdPairs(renderParametersUrlTemplate,
                                            neighborPairs);
        FileUtil.saveJsonFile(outputFileName, renderableCanvasIdPairs);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RedistributeTilePairsClient.class);
}
