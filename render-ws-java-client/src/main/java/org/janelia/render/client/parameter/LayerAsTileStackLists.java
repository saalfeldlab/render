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
 * Lists of stacks for layer-as-tile processing.
 */
public class LayerAsTileStackLists implements Serializable {

    private final String baseDataUrl;
    private final LayerAsTileParameters layerAsTile;

    private final List<StackWithZValues> align2DSfovStacksWithAllZ;
    private final List<StackWithZValues> renderedLayerStacksWithAllZ = new ArrayList<>();
    private final List<StackWithZValues> align3DSfovStacksWithAllZ = new ArrayList<>();

    private final Set<StackId> existingStacks = new HashSet<>();

    public LayerAsTileStackLists(final String baseDataUrl,
                                 final MultiProjectParameters multiProject,
                                 final LayerAsTileParameters layerAsTile)
            throws IOException {

        this.baseDataUrl = baseDataUrl;
        this.layerAsTile = layerAsTile;
        this.align2DSfovStacksWithAllZ = multiProject.buildListOfStackWithAllZ();

        final Map<String, Set<String>> ownerToProjectNames = new HashMap<>();
        for (final StackWithZValues rawStackWithAllZ : this.align2DSfovStacksWithAllZ) {

            final StackId rawSfovStackId = rawStackWithAllZ.stackId();
            final StackId renderedLayerStackId = layerAsTile.getRenderedLayerStackId(rawSfovStackId);
            final StackId align3DSfovStackId = layerAsTile.getAlign3DSfovStackId(rawSfovStackId);

            final List<Double> allZValues = rawStackWithAllZ.zValues();
            this.renderedLayerStacksWithAllZ.add(new StackWithZValues(renderedLayerStackId, allZValues));
            this.align3DSfovStacksWithAllZ.add(new StackWithZValues(align3DSfovStackId, allZValues));

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

    public LayerAsTileParameters getLayerAsTile() {
        return layerAsTile;
    }

    public List<StackWithZValues> getAlign2DSfovStacksWithAllZ() {
        return align2DSfovStacksWithAllZ;
    }

    public  List<StackWithZValues> getRenderedLayerStacksWithAllZ() {
        return renderedLayerStacksWithAllZ;
    }

    public  List<StackWithZValues> getAlign3DSfovStacksWithAllZ() {
        return align3DSfovStacksWithAllZ;
    }

    public List<String> getOwners() {
        return align2DSfovStacksWithAllZ.stream().map(s -> s.stackId().getOwner())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public List<String> getProjectsWithOwner(final String owner) {
        return align2DSfovStacksWithAllZ.stream()
                .filter(s -> s.stackId().getOwner().equals(owner))
                .map(s -> s.stackId().getProject())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public  List<StackWithZValues> getRenderedLayerStacksWithAllZ(final String owner,
                                                                  final String project) {
        return renderedLayerStacksWithAllZ.stream()
                .filter(stackWithZ -> {
                    final StackId stackId = stackWithZ.stackId();
                    return stackId.getOwner().equals(owner) && stackId.getProject().equals(project);
                }).collect(Collectors.toList());
    }

    public boolean isExistingStack(final StackId stackId) {
        return existingStacks.contains(stackId);
    }

}
