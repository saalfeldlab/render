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

import org.janelia.alignment.spec.Bounds;
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

    /**
     * Bounds for each stack in {@link #renderedLayerStacksWithAllZ} (parallel list).
     * These can only be loaded after the rendered layer stacks have been created,
     * so {@link #loadRenderedLayerStackBounds} needs to be called before they are used.
     */
    private final List<Bounds> renderedLayerStackBounds = new ArrayList<>();

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

            final StackId rawSfovStackId = rawStackWithAllZ.getStackId();
            final StackId renderedLayerStackId = layerAsTile.getRenderedLayerStackId(rawSfovStackId);
            final StackId align3DSfovStackId = layerAsTile.getAlign3DSfovStackId(rawSfovStackId);

            final List<Double> allZValues = rawStackWithAllZ.getzValues();
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

    /**
     * Loads the bounds for all rendered layer stacks.
     *
     * @throws IOException
     *   if the bounds for any rendered layer stack cannot be retrieved.
     */
    public void loadRenderedLayerStackBounds()
            throws IOException {

        renderedLayerStackBounds.clear();

        for (final StackWithZValues stackWithZ : renderedLayerStacksWithAllZ) {
            final StackId stackId = stackWithZ.getStackId();
            final RenderDataClient stackClient = new RenderDataClient(baseDataUrl,
                                                                      stackId.getOwner(),
                                                                      stackId.getProject());
            final Bounds stackBounds = stackClient.getStackMetaData(stackId.getStack()).getStackBounds();
            renderedLayerStackBounds.add(stackBounds);
        }
    }

    /**
     * @return the bounds of the specified rendered layer stack.
     *
     * @throws IllegalStateException
     *   if {@link #loadRenderedLayerStackBounds} has not been called.
     *
     * @throws IllegalArgumentException
     *   if the specified stack is not a rendered layer stack for this run.
     */
    public Bounds getRenderedLayerStackBounds(final StackId renderedLayerStackId)
            throws IllegalStateException, IllegalArgumentException {

        if (renderedLayerStackBounds.size() != renderedLayerStacksWithAllZ.size()) {
            throw new IllegalStateException("loadRenderedLayerStackBounds must be called before " +
                                            "requesting the bounds of " + renderedLayerStackId);
        }

        for (int i = 0; i < renderedLayerStacksWithAllZ.size(); i++) {
            if (renderedLayerStacksWithAllZ.get(i).getStackId().equals(renderedLayerStackId)) {
                return renderedLayerStackBounds.get(i);
            }
        }

        throw new IllegalArgumentException("no bounds found for rendered layer stack " + renderedLayerStackId);
    }

    public  List<StackWithZValues> getAlign3DSfovStacksWithAllZ() {
        return align3DSfovStacksWithAllZ;
    }

    public List<String> getOwners() {
        return align2DSfovStacksWithAllZ.stream().map(s -> s.getStackId().getOwner())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public List<String> getProjectsWithOwner(final String owner) {
        return align2DSfovStacksWithAllZ.stream()
                .filter(s -> s.getStackId().getOwner().equals(owner))
                .map(s -> s.getStackId().getProject())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public  List<StackWithZValues> getRenderedLayerStacksWithAllZ(final String owner,
                                                                  final String project) {
        return renderedLayerStacksWithAllZ.stream()
                .filter(stackWithZ -> {
                    final StackId stackId = stackWithZ.getStackId();
                    return stackId.getOwner().equals(owner) && stackId.getProject().equals(project);
                }).collect(Collectors.toList());
    }

    public boolean isExistingStack(final StackId stackId) {
        return existingStacks.contains(stackId);
    }

}
