package org.janelia.render.client.parameter;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.RenderDataClient;

/**
 * Lists of stacks for MFOV-as-tile processing.
 */
public class MFOVAsTileStackLists implements Serializable {

    private final String baseDataUrl;
    private final MFOVAsTileParameters mfovAsTile;

    private final List<StackWithZValues> rawSfovStacksWithAllZ;
    private final List<StackWithZValues> prealignedSfovStacksWithAllZ = new ArrayList<>();
    private final List<StackWithZValues> renderedMfovStacksWithAllZ = new ArrayList<>();
    private final List<StackWithZValues> roughSfovStacksWithAllZ = new ArrayList<>();

    private final Set<StackId> existingStacks = new HashSet<>();

    public MFOVAsTileStackLists(final String baseDataUrl,
                                final MultiProjectParameters multiProject,
                                final MFOVAsTileParameters mfovAsTile)
            throws IOException {

        this.baseDataUrl = baseDataUrl;
        this.mfovAsTile = mfovAsTile;
        this.rawSfovStacksWithAllZ = multiProject.buildListOfStackWithAllZ();

        final Map<String, Set<String>> ownerToProjectNames = new HashMap<>();
        for (final StackWithZValues rawStackWithAllZ : this.rawSfovStacksWithAllZ) {

            final StackId rawSfovStackId = rawStackWithAllZ.getStackId();
            final StackId prealignedSfovStackId = rawSfovStackId.withStackSuffix(mfovAsTile.getPrealignedMfovStackSuffix());
            final StackId dynamicMfovStackId = mfovAsTile.getDynamicMfovStackId(rawSfovStackId);
            final StackId renderedMfovStackId = mfovAsTile.getRenderedMfovStackId(dynamicMfovStackId);
            final StackId roughSfovStackId = mfovAsTile.getRoughSfovStackId(rawSfovStackId);

            final List<Double> allZValues = rawStackWithAllZ.getzValues();
            this.prealignedSfovStacksWithAllZ.add(new StackWithZValues(prealignedSfovStackId, allZValues));
            this.renderedMfovStacksWithAllZ.add(new StackWithZValues(renderedMfovStackId, allZValues));
            this.roughSfovStacksWithAllZ.add(new StackWithZValues(roughSfovStackId, allZValues));

            final Set<String> projectNames = ownerToProjectNames.computeIfAbsent(rawSfovStackId.getOwner(),
                                                                                 k -> new HashSet<>());
            projectNames.add(rawSfovStackId.getProject());
        }

        for (final String owner : ownerToProjectNames.keySet()) {
            for (final String project : ownerToProjectNames.get(owner)) {
                final RenderDataClient projectClient = new RenderDataClient(baseDataUrl, owner, project);
                existingStacks.addAll(projectClient.getProjectStacks());
            }
        }
    }

    public String getBaseDataUrl() {
        return baseDataUrl;
    }

    public MFOVAsTileParameters getMfovAsTile() {
        return mfovAsTile;
    }

    public List<StackWithZValues> getRawSfovStacksWithAllZ() {
        return rawSfovStacksWithAllZ;
    }

    public  List<StackWithZValues> getPrealignedSfovStacksWithAllZ() {
        return prealignedSfovStacksWithAllZ;
    }

    public  List<StackWithZValues> getRenderedMfovStacksWithAllZ() {
        return renderedMfovStacksWithAllZ;
    }

    public  List<StackWithZValues> getRoughSfovStacksWithAllZ() {
        return roughSfovStacksWithAllZ;
    }

    public List<String> getOwners() {
        return rawSfovStacksWithAllZ.stream().map(s -> s.getStackId().getOwner())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public List<String> getProjectsWithOwner(final String owner) {
        return rawSfovStacksWithAllZ.stream()
                .filter(s -> s.getStackId().getOwner().equals(owner))
                .map(s -> s.getStackId().getProject())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public  List<StackWithZValues> getRenderedMfovStacksWithAllZ(final String owner,
                                                                 final String project) {
        return renderedMfovStacksWithAllZ.stream()
                .filter(stackWithZ -> {
                    final StackId stackId = stackWithZ.getStackId();
                    return stackId.getOwner().equals(owner) && stackId.getProject().equals(project);
                }).collect(Collectors.toList());
    }

    public boolean isExistingStack(final StackId stackId) {
        return existingStacks.contains(stackId);
    }

}
