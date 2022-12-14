package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.multisem.OrderedMFOVPair;
import org.janelia.alignment.multisem.UnconnectedMFOVPairsForStack;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for finding MFOVs that are unconnected (or very poorly connected) across adjacent z layers.
 *
 * @author Eric Trautman
 */
public class UnconnectedCrossMFOVClient {

    public static class Parameters
            extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack to process",
                variableArity = true,
                required = true)
        public List<String> stackNames;

        @Parameter(
                names = "--montageStackSuffix",
                description = "Suffix to append to source stack names when creating mfov montage stack names")
        public String montageStackSuffix = "_mfov_montage";

        @Parameter(
                names = "--matchCollection",
                description = "Match collection with unconnected MFOV tile pairs",
                variableArity = true,
                required = true)
        public List<String> matchCollectionNames;

        @Parameter(
                names = "--minPairsForConnection",
                description = "Minimum number of connected SFOV tile pairs needed to consider an MFOV connected",
                variableArity = true,
                required = true)
        public Integer minPairsForConnection;

        @Parameter(
                names = "--unconnectedMFOVPairsDirectory",
                description = "Directory to store unconnected MFOV pair results (omit to simply print results to stdout)"
        )
        public String unconnectedMFOVPairsDirectory;

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final UnconnectedCrossMFOVClient client = new UnconnectedCrossMFOVClient(parameters);
                client.findUnconnectedMFOVs();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient renderDataClient;


    UnconnectedCrossMFOVClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    public void findUnconnectedMFOVs()
            throws IOException {

        LOG.info("findUnconnectedMFOVs: entry");

        if (parameters.stackNames.size() != parameters.matchCollectionNames.size()) {
            throw new IOException("must specify same number of --stack and --matchCollection parameters");
        }

        final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks = new ArrayList<>();

        for (int i = 0; i < parameters.stackNames.size(); i++) {
            final StackId renderStackId = new StackId(parameters.renderWeb.owner,
                                                      parameters.renderWeb.project,
                                                      parameters.stackNames.get(i));
            final MatchCollectionId matchCollectionId = new MatchCollectionId(parameters.renderWeb.owner,
                                                                              parameters.matchCollectionNames.get(i));

            final String mFOVMontageStackName = renderStackId.getStack() + parameters.montageStackSuffix;
            final UnconnectedMFOVPairsForStack
                    unconnectedMFOVPairsForStack = new UnconnectedMFOVPairsForStack(renderStackId,
                                                                                    mFOVMontageStackName,
                                                                                    matchCollectionId);
            final Map<Double, Set<String>> zToSectionIdsMap =
                    renderDataClient.getStackZToSectionIdsMap(renderStackId.getStack(),
                                                              null,
                                                              null,
                                                              null);

            for (final Double pZ : zToSectionIdsMap.keySet().stream().sorted().collect(Collectors.toList())) {
                final Double qZ = pZ + 1.0;
                if (zToSectionIdsMap.containsKey(qZ)) {
                    addUnconnectedMFOVPairsForLayer(pZ, qZ, unconnectedMFOVPairsForStack);
                } else {
                    LOG.info("findUnconnectedMFOVs: skipping z {} to {} pair since z {} does not exist in {}",
                             pZ, qZ, qZ, renderStackId.getStack());
                }
            }

            LOG.info("findUnconnectedMFOVs: found {} unconnected MFOV pairs across all z in stack {}",
                     unconnectedMFOVPairsForStack.size(), renderStackId.getStack());

            if (unconnectedMFOVPairsForStack.size() > 0) {
                unconnectedMFOVsForAllStacks.add(unconnectedMFOVPairsForStack);
            }
        }

        if (parameters.unconnectedMFOVPairsDirectory == null) {
            LOG.info("findUnconnectedMFOVs: unconnected MFOV pairs for all stacks are:");
            for (final UnconnectedMFOVPairsForStack pairsForStack : unconnectedMFOVsForAllStacks) {
                System.out.println(JsonUtils.FAST_MAPPER.writeValueAsString(pairsForStack));
            }
        } else {
            storeUnconnectedMFOVPairs(unconnectedMFOVsForAllStacks);
        }
    }

    private void storeUnconnectedMFOVPairs(final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks)
            throws IOException {

        for (final UnconnectedMFOVPairsForStack pairsForStack : unconnectedMFOVsForAllStacks) {
            final String stack = pairsForStack.getRenderStackId().getStack();
            for (final UnconnectedMFOVPairsForStack pairsForZ : UnconnectedMFOVPairsForStack.groupByPZ(pairsForStack)) {
                final int pZ = (int) pairsForZ.getUnconnectedMFOVPairs().get(0).getP().getZ();
                final String fileName = "unconnected_mfov_pairs." + stack + "." + pZ + ".json";
                final Path storagePath = Paths.get(parameters.unconnectedMFOVPairsDirectory,
                                                   fileName).toAbsolutePath();
                FileUtil.saveJsonFile(storagePath.toString(), pairsForZ);
            }
        }
    }

    public void addUnconnectedMFOVPairsForLayer(final Double pZ,
                                                final Double qZ,
                                                final UnconnectedMFOVPairsForStack unconnectedMFOVs)
            throws IOException {

        final String stack = unconnectedMFOVs.getRenderStackId().getStack();
        final MatchCollectionId matchCollectionId = unconnectedMFOVs.getMatchCollectionId();

        final String pGroupId = String.valueOf(pZ);
        final String qGroupId = String.valueOf(qZ);

        final List<String> mFOVNames = Utilities.getMFOVNames(renderDataClient, stack, pZ);

        final Map<String, Integer> mFOVToSFOVPairCount = new HashMap<>();
        mFOVNames.forEach(name -> mFOVToSFOVPairCount.put(name, 0));

        final RenderDataClient matchClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                  matchCollectionId.getOwner(),
                                                                  matchCollectionId.getName());

        for (final CanvasMatches canvasMatches : matchClient.getMatchesBetweenGroups(pGroupId,
                                                                                     qGroupId,
                                                                                     true)) {
            final String pMFOVName = Utilities.getMFOVForTileId(canvasMatches.getpId());
            final String qMFOVName = Utilities.getMFOVForTileId(canvasMatches.getqId());
            final Integer sFOVPairCount = mFOVToSFOVPairCount.get(pMFOVName);
            if ((sFOVPairCount != null) && (pMFOVName.equals(qMFOVName))) {
                mFOVToSFOVPairCount.put(pMFOVName, sFOVPairCount + 1);
            }
        }

        int unconnectedPairCount = 0;
        for (final String mFOVName : mFOVNames) {
            final Integer sFOVPairCount = mFOVToSFOVPairCount.get(mFOVName);
            if ((sFOVPairCount == null) || (sFOVPairCount < parameters.minPairsForConnection)) {
                unconnectedMFOVs.addUnconnectedPair(new OrderedMFOVPair(new LayerMFOV(pZ, mFOVName),
                                                                        new LayerMFOV(qZ, mFOVName)));
                unconnectedPairCount++;
            }
        }

        LOG.info("addUnconnectedMFOVPairsForLayer: found {} unconnected MFOV pairs between z {} and {} in {}",
                 unconnectedPairCount, pZ, qZ, stack);
    }

    private static final Logger LOG = LoggerFactory.getLogger(UnconnectedCrossMFOVClient.class);
}
