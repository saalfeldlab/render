package org.janelia.render.trakem2;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.RenderDataClient;

import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Utils;

/**
 * This plug-in exports TrakEM patch data into a render web services stack.
 * All transforms are exported as they exist in TrakEM2.
 * All mask paths are exported as they exist in TrakEM2 (even when differing paths reference the same mask bytes).
 *
 * @author Eric Trautman
 */
public class ExportToRenderAsIs_Plugin
        implements PlugIn {

    static void flattenTransforms(final CoordinateTransform ct,
                                  final List<CoordinateTransform> flattenedList) {
        if (ct instanceof CoordinateTransformList) {
            @SuppressWarnings("unchecked")
            final CoordinateTransformList<CoordinateTransform> ctList =
                    (CoordinateTransformList<CoordinateTransform>) ct;
            for (final CoordinateTransform ctListItem : ctList.getList(null)) {
                flattenTransforms(ctListItem, flattenedList);
            }
        } else {
            flattenedList.add(ct);
        }
    }

    @Override
    public void run(final String arg) {

        Utils.log("\norg.janelia.render.trakem2.ExportToRenderAsIs_Plugin.run: entry");

        if (exportData == null) {
            exportData = new ExportData();
        }
        exportData.layerRange.setLayerSet();

        final boolean wasCancelled = exportData.collectInputs();
        if (! wasCancelled) {
            try {
                exportPatches();
            } catch (final Exception e) {
                e.printStackTrace(System.out);
                IJ.error("Export to Render Stack Failed",
                         "Caught exception:\n" + e.getMessage());
            }
        }

        Utils.log("\norg.janelia.render.trakem2.ExportToRenderAsIs_Plugin.run: exit");
    }

    private void exportPatches()
            throws IOException {

        final ProcessTimer timer = new ProcessTimer();

        for (final Layer layer : exportData.layerRange.getLayersInRange()) {

            final List<Displayable> displayableList = layer.getDisplayables(Patch.class,
                                                                            exportData.onlyExportVisiblePatches,
                                                                            true);

            if (displayableList.size() > 0) {

                final ResolvedTileSpecCollection resolvedTiles = new ResolvedTileSpecCollection();

                for (final Displayable displayable : displayableList) {
                    final Patch patch = (Patch) displayable;

                    final TileSpec tileSpec = new TileSpec();

                    if (exportData.usePatchTitleForTileId) {
                        tileSpec.setTileId(patch.getTitle());
                    } else {
                        tileSpec.setTileId(patch.getUniqueIdentifier());
                    }

                    tileSpec.setZ(layer.getZ());
                    tileSpec.setWidth((double) patch.getOWidth());
                    tileSpec.setHeight((double) patch.getOHeight());

                    final ImageAndMask imageAndMask;
                    if (patch.hasAlphaMask()) {
                        imageAndMask = new ImageAndMask(new File(patch.getFilePath()),
                                                        new File(patch.getAlphaMaskFilePath()));

                    } else {
                        imageAndMask = new ImageAndMask(new File(patch.getFilePath()),
                                                        null);
                    }

                    final ChannelSpec channelSpec = new ChannelSpec(null, patch.getMin(), patch.getMax());
                    channelSpec.putMipmap(0, imageAndMask);
                    tileSpec.addChannel(channelSpec);

                    final CoordinateTransform ct = patch.getFullCoordinateTransform();
                    final List<CoordinateTransform> transforms = new ArrayList<>();
                    flattenTransforms(ct, transforms);

                    final List<TransformSpec> transformSpecs = new ArrayList<>(transforms.size());
                    for (final CoordinateTransform t : transforms) {
                        transformSpecs.add(new LeafTransformSpec(t.getClass().getCanonicalName(),
                                                                 t.toDataString()));
                    }

                    tileSpec.addTransformSpecs(transformSpecs);

                    tileSpec.deriveBoundingBox(patch.getMeshResolution(), true);

                    final String sectionId = String.valueOf(layer.getZ());
                    final Double stageX = tileSpec.getMinX();
                    final Double stageY = tileSpec.getMinY();
                    final LayoutData layout = new LayoutData(sectionId,
                                                             null,
                                                             null,
                                                             null,
                                                             null,
                                                             stageX,
                                                             stageY,
                                                             null);
                    tileSpec.setLayout(layout);

                    resolvedTiles.addTileSpecToCollection(tileSpec);

                    if (timer.hasIntervalPassed()) {
                        Utils.log("exportPatches: updated " + resolvedTiles.getTileCount() + " out of " +
                                  displayableList.size() +" tile specs for z " + layer.getZ());
                    }
                }

                exportData.targetRenderClient.saveResolvedTiles(resolvedTiles, exportData.targetRenderStack, layer.getZ());

                Utils.log("exportPatches: exported " + displayableList.size() +
                          " tile specs for z " + layer.getZ());
            }

        }

        if (exportData.completeStackAfterExport) {
            exportData.targetRenderClient.setStackState(exportData.targetRenderStack,
                                                        StackMetaData.StackState.COMPLETE);
        }

    }

    private static ExportData exportData = null;

    private class ExportData {

        private String baseDataUrl;

        private final LayerRange layerRange;
        private boolean onlyExportVisiblePatches;
        private boolean usePatchTitleForTileId;
        private String targetRenderOwner;
        private String targetRenderProject;
        private String targetRenderStack;
        private double stackResolutionX;
        private double stackResolutionY;
        private double stackResolutionZ;
        private boolean completeStackAfterExport;

        private RenderDataClient targetRenderClient;

        ExportData() {
            baseDataUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
            layerRange = new LayerRange(1.0, 2.0);
            onlyExportVisiblePatches = true;
            usePatchTitleForTileId = false;
            targetRenderOwner = "trautmane";
            targetRenderProject = "export_tests";
            targetRenderStack = "test_a";
            stackResolutionX = 4.0;
            stackResolutionY = 4.0;
            stackResolutionZ = 35.0;
            completeStackAfterExport = true;
        }

        boolean setParametersFromDialog() {

            final GenericDialog dialog = new GenericDialog("Export Parameters");

            final int defaultTextColumns = 100;
            dialog.addStringField("Render Web Services Base URL", baseDataUrl, defaultTextColumns);
            layerRange.addFieldsToDialog(dialog);
            dialog.addCheckbox("Only Export Visible Patches", onlyExportVisiblePatches);
            dialog.addCheckbox("Use Patch Title For Tile ID", usePatchTitleForTileId);
            dialog.addMessage("  note: leave unchecked to use TrakEM2 patch object ID as tile ID");
            dialog.addStringField("Target Render Stack Owner", targetRenderOwner, defaultTextColumns);
            dialog.addStringField("Target Render Stack Project", targetRenderProject, defaultTextColumns);
            dialog.addStringField("Target Render Stack Name", targetRenderStack, defaultTextColumns);
            dialog.addNumericField("Stack Resolution X (nm/pixel)", stackResolutionX, 0);
            dialog.addNumericField("Stack Resolution Y (nm/pixel)", stackResolutionY, 0);
            dialog.addNumericField("Stack Resolution Z (nm/pixel)", stackResolutionZ, 0);
            dialog.addCheckbox("Complete Stack After Export", completeStackAfterExport);

            dialog.showDialog();
            dialog.repaint(); // seems to help with missing dialog elements, but shouldn't be necessary

            final boolean wasCancelled = dialog.wasCanceled();

            if (! wasCancelled) {
                baseDataUrl = dialog.getNextString().trim();
                layerRange.setFieldsFromDialog(dialog);
                onlyExportVisiblePatches = dialog.getNextBoolean();
                usePatchTitleForTileId = dialog.getNextBoolean();
                targetRenderOwner = dialog.getNextString().trim();
                targetRenderProject = dialog.getNextString().trim();
                targetRenderStack =  dialog.getNextString().trim();
                stackResolutionX = dialog.getNextNumber();
                stackResolutionY = dialog.getNextNumber();
                stackResolutionZ = dialog.getNextNumber();
                completeStackAfterExport = dialog.getNextBoolean();
            }

            return wasCancelled;
        }

        boolean collectInputs() {

            boolean wasCancelled = setParametersFromDialog();

            if (! wasCancelled) {

                try {
                    layerRange.validate();

                    final StackId targetStackId = new StackId(targetRenderOwner, targetRenderProject, targetRenderStack);
                    targetRenderClient = new RenderDataClient(baseDataUrl,
                                                              targetStackId.getOwner(),
                                                              targetStackId.getProject());

                    final StackVersion targetStackVersion = new StackVersion(new Date(),
                                                                             null,
                                                                             null,
                                                                             null,
                                                                             stackResolutionX,
                                                                             stackResolutionY,
                                                                             stackResolutionZ,
                                                                             null,
                                                                             null);
                    final StackMetaData targetStackMetaData = new StackMetaData(targetStackId,
                                                                                targetStackVersion);
                    targetRenderClient.setupDerivedStack(targetStackMetaData, targetRenderStack);

                } catch (final Exception e) {
                    e.printStackTrace(System.out);
                    IJ.error("Invalid Render Parameters",
                             "Specified parameters caused the following exception:\n" + e.getMessage());
                    wasCancelled = collectInputs();
                }
            }

            return wasCancelled;
        }

    }
}
