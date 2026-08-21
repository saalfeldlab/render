package org.janelia.render.client.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiSEMTileRemovalParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client that removes tiles from a multi-SEM stack.
 * <p>
 * Removal is done in-place (the stack is set to the LOADING state, changed, and then completed)
 * and supports the following operations:
 * <ul>
 *     <li>removal of all tiles in one or more scans (see {@code --scan})</li>
 *     <li>removal of all tiles for one or more MFOVs in a specific scan (see {@code --scanMfov})</li>
 *     <li>renumbering of the layers that remain after layer removal (see {@code --collapseStack})</li>
 * </ul>
 * Scans are identified by name rather than by z value so that removal is idempotent.
 * Note that stack bounds and other stats are recalculated when the stack is completed at the end of removal.
 */
public class MultiSEMTileRemovalClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack from which tiles should be removed",
                required = true)
        public String stack;

        @ParametersDelegate
        public MultiSEMTileRemovalParameters tileRemoval = new MultiSEMTileRemovalParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                LOG.info("runClient: entry, parameters={}", parameters);

                parameters.tileRemoval.validate();

                final MultiSEMTileRemovalClient client = new MultiSEMTileRemovalClient();
                final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
                client.removeTiles(dataClient,
                                   parameters.stack,
                                   parameters.tileRemoval);
            }
        };
        clientRunner.run();
    }

    public MultiSEMTileRemovalClient() {
    }

    /**
     * Removes the specified layers and MFOVs from the specified stack,
     * collapsing the remaining layers if that was requested,
     * and then completes the stack.
     *
     * @param  dataClient   client for the stack's owner and project.
     * @param  stack        stack from which tiles should be removed.
     * @param  tileRemoval  parameters identifying what should be removed.
     *
     * @throws IOException
     *   if any request fails.
     */
    public void removeTiles(final RenderDataClient dataClient,
                            final String stack,
                            final MultiSEMTileRemovalParameters tileRemoval)
            throws IOException {

        LOG.info("removeTiles: entry, stack={}, tileRemoval={}", stack, tileRemoval);

        final List<Double> stackZValues = getStackZValues(dataClient, stack);

        // map the requested scan names to this stack's z values so that removal is idempotent
        final Map<String, Double> scanNameToZMap = buildScanNameToZMap(dataClient, stack, stackZValues, tileRemoval);

        // work out (and check) what should be removed before changing anything
        final List<Double> zValuesToRemove = getZValuesToRemove(stack, tileRemoval, stackZValues, scanNameToZMap);
        final List<Double> sortedRemainingZValues = stackZValues.stream()
                .filter(z -> ! zValuesToRemove.contains(z))
                .sorted()
                .collect(Collectors.toList());

        final Map<Double, Set<String>> zToMfovNamesMap = buildZToMfovNamesMap(tileRemoval, scanNameToZMap);

        validateMfovNames(dataClient, stack, zToMfovNamesMap, zValuesToRemove);

        // leave the stack alone if everything requested has already been removed
        if (zValuesToRemove.isEmpty() && zToMfovNamesMap.isEmpty()) {
            LOG.info("removeTiles: exit, nothing to remove from {}", stack);
            return;
        }

        dataClient.setStackState(stack, StackMetaData.StackState.LOADING);

        removeLayers(dataClient, stack, zValuesToRemove, sortedRemainingZValues.size());

        if (! zToMfovNamesMap.isEmpty()) {

            int removedTileCount = 0;
            for (final Double z : zToMfovNamesMap.keySet()) {
                final Set<String> mfovNames = zToMfovNamesMap.get(z);
                if (sortedRemainingZValues.contains(z)) {
                    removedTileCount += removeMfovTilesForZ(dataClient, stack, z, mfovNames);
                } else {
                    LOG.warn("removeTiles: skipping MFOVs {} because z {} is not in {}", mfovNames, z, stack);
                }
            }

            LOG.info("removeTiles: removed {} MFOV tiles from {} layers of {}",
                     removedTileCount, zToMfovNamesMap.size(), stack);
        }

        if (tileRemoval.collapseStack) {
            collapseStack(dataClient, stack, zValuesToRemove, sortedRemainingZValues);
        }

        dataClient.setStackState(stack, StackMetaData.StackState.COMPLETE);

        LOG.info("removeTiles: exit, stack={}", stack);
    }

    /**
     * @return the sorted z values that exist in the stack before removal.
     *
     * @throws IOException
     *   if the stack does not exist or the request fails.
     */
    private List<Double> getStackZValues(final RenderDataClient dataClient,
                                         final String stack)
            throws IOException {

        // fetch metadata first so that the run fails fast for stacks that do not exist
        final StackMetaData stackMetaData = dataClient.getStackMetaData(stack);

        final List<Double> stackZValues = dataClient.getStackZValues(stack);

        LOG.info("getStackZValues: {} is in the {} state with {} layers",
                 stack, stackMetaData.getState(), stackZValues.size());

        return stackZValues;
    }

    /**
     * @return map of z values to the simple names of the MFOVs that should be removed from each of those layers
     *         (scan names that are not in the specified map are excluded).
     */
    private Map<Double, Set<String>> buildZToMfovNamesMap(final MultiSEMTileRemovalParameters tileRemoval,
                                                          final Map<String, Double> scanNameToZMap) {

        final Map<Double, Set<String>> zToMfovNamesMap = new TreeMap<>();

        for (final Map.Entry<String, Set<String>> entry : tileRemoval.getScanToMfovNamesMap().entrySet()) {
            final Double z = scanNameToZMap.get(entry.getKey());
            if (z != null) {
                zToMfovNamesMap.computeIfAbsent(z, k -> new TreeSet<>()).addAll(entry.getValue());
            }
        }

        return zToMfovNamesMap;
    }

    /**
     * Maps each scan name requested for removal to the z value of the layer that contains it.
     * The scan name for a layer is derived from the first tile in that layer,
     * so all tiles in a layer are assumed to come from the same scan.
     * Requested scans that are no longer in the stack are simply logged
     * (they have already been removed, so there is nothing left to do for them).
     *
     * @return map of scan names to z values for the layers that were checked.
     *
     * @throws IOException
     *   if a scan exists in more than one layer or if any request fails.
     */
    private Map<String, Double> buildScanNameToZMap(final RenderDataClient dataClient,
                                                    final String stack,
                                                    final List<Double> stackZValues,
                                                    final MultiSEMTileRemovalParameters tileRemoval)
            throws IOException {

        final Set<String> requestedScanNames = tileRemoval.buildScanNamesSet();
        final Map<String, Double> scanNameToZMap = new HashMap<>();

        for (final Double z : stackZValues) {

            final List<String> tileIds = dataClient.getTileIdsForZ(stack, z);

            if (tileIds.isEmpty()) {

                LOG.warn("buildScanNameToZMap: no tiles exist for z {} in {}", z, stack);

            } else {

                final String scanName = MultiSemUtilities.getScanStringForTileId(tileIds.get(0));
                final Double previousZ = scanNameToZMap.put(scanName, z);

                if (previousZ != null) {
                    throw new IOException("scan " + scanName + " exists in z " + previousZ + " and z " + z +
                                          " of " + stack);
                }

                if (scanNameToZMap.keySet().containsAll(requestedScanNames)) {
                    break; // all requested scans have been found, so stop looking
                }
            }
        }

        final List<String> missingScanNames = requestedScanNames.stream()
                .filter(scanName -> ! scanNameToZMap.containsKey(scanName))
                .collect(Collectors.toList());

        if (! missingScanNames.isEmpty()) {
            LOG.info("buildScanNameToZMap: nothing to remove for scan(s) {} because they no longer exist in {}",
                     missingScanNames, stack);
        }

        LOG.info("buildScanNameToZMap: mapped {} of {} requested scan(s) to z values in {}",
                 (requestedScanNames.size() - missingScanNames.size()), requestedScanNames.size(), stack);

        return scanNameToZMap;
    }

    /**
     * @return the sorted z values for the layers that should be removed.
     *
     * @throws IllegalStateException
     *   if removing the layers would leave the stack empty.
     */
    private List<Double> getZValuesToRemove(final String stack,
                                            final MultiSEMTileRemovalParameters tileRemoval,
                                            final List<Double> stackZValues,
                                            final Map<String, Double> scanNameToZMap)
            throws IllegalStateException {

        final List<Double> zValuesToRemove = tileRemoval.getSortedScanNames().stream()
                .map(scanNameToZMap::get)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // check before anything is changed so that a stack is not left without any layers
        if (zValuesToRemove.size() == stackZValues.size()) {
            throw new IllegalStateException("all " + stackZValues.size() + " layers would be removed from " +
                                            stack + ", delete the stack instead if that is what you want");
        }

        return zValuesToRemove;
    }

    /**
     * Confirms that each MFOV requested for removal exists in its layer.
     * MFOVs in layers that are being completely removed are logged and skipped.
     *
     * @throws IOException
     *   if a requested MFOV does not exist or if any request fails.
     */
    private void validateMfovNames(final RenderDataClient dataClient,
                                   final String stack,
                                   final Map<Double, Set<String>> zToMfovNamesMap,
                                   final List<Double> zValuesToRemove)
            throws IOException {

        for (final Map.Entry<Double, Set<String>> entry : zToMfovNamesMap.entrySet()) {

            final Double z = entry.getKey();
            final Set<String> mfovNames = entry.getValue();

            if (zValuesToRemove.contains(z)) {

                LOG.warn("validateMfovNames: MFOVs {} do not need to be removed because all of z {} " +
                         "is being removed from {}", mfovNames, z, stack);

            } else {

                final Set<String> layerMfovNames = dataClient.getTileIdsForZ(stack, z).stream()
                        .map(MultiSemUtilities::getSimpleMfovForTileId)
                        .collect(Collectors.toSet());

                for (final String mfovName : mfovNames) {
                    if (! layerMfovNames.contains(mfovName)) {
                        throw new IOException("requested MFOV " + mfovName + " does not exist in z " + z +
                                              " of " + stack);
                    }
                }
            }
        }
    }

    /**
     * Removes all tiles in each of the specified layers.
     *
     * @throws IOException
     *   if any request fails.
     */
    private void removeLayers(final RenderDataClient dataClient,
                              final String stack,
                              final List<Double> zValuesToRemove,
                              final int remainingLayerCount)
            throws IOException {

        for (final Double z : zValuesToRemove) {
            LOG.info("removeLayers: removing z {} from {}", z, stack);
            dataClient.deleteStack(stack, z);
        }

        LOG.info("removeLayers: removed {} layers from {} leaving {} layers",
                 zValuesToRemove.size(), stack, remainingLayerCount);
    }

    /**
     * Removes all tiles for the specified MFOVs from one layer.
     *
     * @return the number of tiles removed.
     *
     * @throws IOException
     *   if any request fails.
     */
    private int removeMfovTilesForZ(final RenderDataClient dataClient,
                                    final String stack,
                                    final Double z,
                                    final Set<String> mfovNames)
            throws IOException {

        final List<String> tileIdsToRemove = dataClient.getTileIdsForZ(stack, z).stream()
                .filter(tileId -> MultiSEMTileRemovalParameters.isTileInMfovs(tileId, mfovNames))
                .sorted()
                .collect(Collectors.toList());

        if (tileIdsToRemove.isEmpty()) {
            LOG.warn("removeMfovTilesForZ: no tiles in z {} of {} are in MFOVs {}",
                     z, stack, mfovNames);
        } else {
            LOG.info("removeMfovTilesForZ: removing {} tiles from z {} of {} for MFOVs {}",
                     tileIdsToRemove.size(), z, stack, mfovNames);
            for (final String tileId : tileIdsToRemove) {
                dataClient.deleteStackTile(stack, tileId);
            }
        }

        return tileIdsToRemove.size();
    }

    /**
     * Decreases the z value for each remaining layer by one for each removed layer before it.
     * <p>
     * NOTE: layers are moved one at a time in ascending z order because each layer's tiles
     *       are identified by a query for its current z value.
     *
     * @throws IOException
     *   if any request fails.
     */
    private void collapseStack(final RenderDataClient dataClient,
                               final String stack,
                               final List<Double> zValuesToRemove,
                               final List<Double> sortedRemainingZValues)
            throws IOException {

        int movedLayerCount = 0;

        for (final Double z : sortedRemainingZValues) {

            final Double collapsedZ = getCollapsedZ(z, zValuesToRemove);

            if (! collapsedZ.equals(z)) {

                final List<String> tileIds = dataClient.getTileIdsForZ(stack, z);

                LOG.info("collapseStack: moving {} tiles in {} from z {} to z {}",
                         tileIds.size(), stack, z, collapsedZ);

                if (! tileIds.isEmpty()) {
                    dataClient.updateZForTiles(stack, collapsedZ, tileIds);
                    movedLayerCount++;
                }
            }
        }

        LOG.info("collapseStack: moved {} layers in {}", movedLayerCount, stack);
    }

    /**
     * @return the collapsed z value for the specified z
     *         (the specified z decreased by one for each removed layer before it).
     */
    private Double getCollapsedZ(final Double z,
                                 final List<Double> zValuesToRemove) {
        int removedLayerCount = 0;
        for (final Double removedZ : zValuesToRemove) {
            if (removedZ < z) {
                removedLayerCount++;
            }
        }
        return z - removedLayerCount;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiSEMTileRemovalClient.class);
}
