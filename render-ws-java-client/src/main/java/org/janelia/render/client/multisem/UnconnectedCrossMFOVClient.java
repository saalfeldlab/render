package org.janelia.render.client.multisem;

import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
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
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.UnconnectedCrossMFOVParameters;
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
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public UnconnectedCrossMFOVParameters core = new UnconnectedCrossMFOVParameters();
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
                final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks = client.findUnconnectedMFOVs();
                client.logOrStoreUnconnectedMFOVPairs(unconnectedMFOVsForAllStacks);
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;


    public UnconnectedCrossMFOVClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public List<UnconnectedMFOVPairsForStack> findUnconnectedMFOVs()
            throws IOException {

        final RenderDataClient renderDataClient = parameters.multiProject.getDataClient();
        // override --zValuesPerBatch with Integer.MAX_VALUE because all z layers in each stack are needed for patching
        final List<StackWithZValues> stackWithZList =
                parameters.multiProject.stackIdWithZ.getStackWithZList(renderDataClient,
                                                                       Integer.MAX_VALUE);
        final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks = new ArrayList<>();
        for (final StackWithZValues stackWithZ : stackWithZList) {
            final UnconnectedMFOVPairsForStack unconnectedMFOVPairsForStack =
                    findUnconnectedMFOVs(stackWithZ,
                                         parameters.multiProject.deriveMatchCollectionNamesFromProject,
                                         renderDataClient);
            if (unconnectedMFOVPairsForStack.size() > 0) {
                unconnectedMFOVsForAllStacks.add(unconnectedMFOVPairsForStack);
            }
        }
        return unconnectedMFOVsForAllStacks;
    }

    public UnconnectedMFOVPairsForStack findUnconnectedMFOVs(final StackWithZValues stackWithZ,
                                                             final boolean deriveMatchCollectionNamesFromProject,
                                                             final RenderDataClient renderDataClient)
            throws IOException {

        LOG.info("findUnconnectedMFOVs: entry, stackWithZ={}", stackWithZ);

        final StackId renderStackId = stackWithZ.getStackId();
        final MatchCollectionId matchCollectionId =
                renderStackId.getDefaultMatchCollectionId(deriveMatchCollectionNamesFromProject);

        final String mFOVMontageStackName = renderStackId.getStack() + parameters.core.montageStackSuffix;
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
                addUnconnectedMFOVPairsForLayer(pZ, qZ, unconnectedMFOVPairsForStack, renderDataClient);
            } else {
                LOG.info("findUnconnectedMFOVs: skipping z {} to {} pair since z {} does not exist in {}",
                         pZ, qZ, qZ, renderStackId.getStack());
            }
        }

        LOG.info("findUnconnectedMFOVs: found {} unconnected MFOV pairs across all z in stack {}",
                 unconnectedMFOVPairsForStack.size(), renderStackId.getStack());

        return unconnectedMFOVPairsForStack;
    }

    public void logOrStoreUnconnectedMFOVPairs(final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks)
            throws IOException {
        if (parameters.core.unconnectedMFOVPairsDirectory == null) {
            logUnconnectedMFOVPairs(unconnectedMFOVsForAllStacks);
        } else {
            storeUnconnectedMFOVPairs(unconnectedMFOVsForAllStacks,
                                      parameters.core.unconnectedMFOVPairsDirectory,
                                      parameters.multiProject.getDataClient());
        }
    }

    public static void logUnconnectedMFOVPairs(final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks)
            throws JsonProcessingException {
        LOG.info("findUnconnectedMFOVs: unconnected MFOV pairs for all stacks are: \n{}",
                 JsonUtils.FAST_MAPPER.writeValueAsString(unconnectedMFOVsForAllStacks));
    }

    public static void storeUnconnectedMFOVPairs(final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks,
                                                 final String unconnectedMFOVPairsDirectory,
                                                 final RenderDataClient renderDataClient)
            throws IOException {

        for (final UnconnectedMFOVPairsForStack pairsForStack : unconnectedMFOVsForAllStacks) {
            final String stack = pairsForStack.getRenderStackId().getStack();
            final List<Double> zValues = renderDataClient.getStackZValues(stack);
            final int lastPZ = zValues.size() > 1 ? zValues.get(zValues.size() - 2).intValue() : -1;
            for (final UnconnectedMFOVPairsForStack pairsForZ : UnconnectedMFOVPairsForStack.groupByPZ(pairsForStack)) {
                final int pZ = (int) pairsForZ.getUnconnectedMFOVPairs().get(0).getP().getZ();
                final String fileNameSuffix = pZ == lastPZ ? ".lastPZ.json" : ".json";
                final String fileName = "unconnected_mfov_pairs." + stack + "." + pZ + fileNameSuffix;
                final Path storagePath = Paths.get(unconnectedMFOVPairsDirectory,
                                                   fileName).toAbsolutePath();
                FileUtil.saveJsonFile(storagePath.toString(), Collections.singletonList(pairsForZ));
            }
        }
    }

    public void addUnconnectedMFOVPairsForLayer(final Double pZ,
                                                final Double qZ,
                                                final UnconnectedMFOVPairsForStack unconnectedMFOVs,
                                                final RenderDataClient renderDataClient)
            throws IOException {

        final String stack = unconnectedMFOVs.getRenderStackId().getStack();
        final MatchCollectionId matchCollectionId = unconnectedMFOVs.getMatchCollectionId();

        final String pGroupId = String.valueOf(pZ);
        final String qGroupId = String.valueOf(qZ);

        final List<String> mFOVNames = Utilities.getMFOVNames(renderDataClient, stack, pZ);

        final Map<String, Integer> mFOVToSFOVPairCount = new HashMap<>();
        mFOVNames.forEach(name -> mFOVToSFOVPairCount.put(name, 0));

        final RenderDataClient matchClient = renderDataClient.buildClient(matchCollectionId.getOwner(),
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
            if ((sFOVPairCount == null) || (sFOVPairCount < parameters.core.minPairsForConnection)) {
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
