package org.janelia.render.trakem2;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.CoordinateTransform;
import mpicbg.trakem2.transform.TranslationModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.RenderDataClient;

import ini.trakem2.Project;
import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Utils;

/**
 * This plug-in imports render web services stack tile data into a TrakEM project.
 *
 * @author Eric Trautman
 */
public class ImportFromRender_Plugin
        implements PlugIn {

    @Override
    public void run(final String arg) {

        final PluginArgument pluginArgument = PluginArgument.checkedValueOf(arg, this);

        if (pluginArgument != null) {

            Utils.log("\norg.janelia.render.trakem2.ImportFromRender_Plugin.run: entry");

            final List<Project> projectList = Project.getProjects();

            final boolean isNewProject = PluginArgument.NEW_PROJECT.equals(pluginArgument) ||
                                         (projectList.size() == 0);

            final boolean isImportLayer = PluginArgument.IMPORT_LAYER.equals(pluginArgument);


            if (isNewProject) {

                // need to set this to see dialog title for storage folder on Mac
                Prefs.useJFileChooser = true;

                final DirectoryChooser chooser = new DirectoryChooser("Select storage folder");
                String storageFolder = chooser.getDirectory();

                if (storageFolder == null) {
                    Utils.log("org.janelia.render.trakem2.ImportFromRender_Plugin.run: cancelled");
                    return;
                }

                storageFolder = storageFolder.replace('\\', '/');

                final Project trakProject = Project.newFSProject("blank", null, storageFolder, false);
                final LayerSet trakLayerSet = trakProject.getRootLayerSet();

                importData = new ImportData(trakProject, trakLayerSet);

            } else if (isImportLayer) {

                if (importData == null) {

                    final Project trakProject;
                    final LayerSet trakLayerSet;
                    final Display front = Display.getFront();
                    if (front == null) {
                        trakProject = projectList.get(0);
                        trakLayerSet = trakProject.getRootLayerSet();
                    } else {
                        trakLayerSet = front.getLayerSet();
                        trakProject = trakLayerSet.getProject();
                    }

                    importData = new ImportData(trakProject, trakLayerSet);

                }

            }

            if (isNewProject || isImportLayer) {
                final boolean wasCancelled = importData.loadRenderData(isNewProject);
                if (! wasCancelled) {
                    convertTileSpecsToPatches();
                }
            }

            Utils.log("\norg.janelia.render.trakem2.ImportFromRender_Plugin.run: exit");
        }

    }

    private void convertTileSpecsToPatches() {

        double thickness = 35.0;
        final StackVersion stackVersion = importData.stackMetaData.getCurrentVersion();
        if (stackVersion != null) {
            final Double resolutionZ = stackVersion.getStackResolutionZ();
            if (resolutionZ != null) {
                thickness = resolutionZ;
            }
        }

        final Loader trakLoader = importData.trakProject.getLoader();
        Layer trakLayer;

        final Map<String, ByteProcessor> maskPathToProcessor =  new HashMap<>();
        ByteProcessor maskProcessor;

        final List<TileSpec> tileSpecList = new ArrayList<>();
        for (final RenderParameters layerRenderParameters : importData.layerRenderParametersList) {
            tileSpecList.addAll(layerRenderParameters.getTileSpecs());
        }

        final ProcessTimer timer = new ProcessTimer();

        final StringBuilder skippedMasksCsvData = new StringBuilder();
        int tileCount = 0;
        int type = importData.imagePlusType;
        for (final TileSpec tileSpec : tileSpecList) {

            final String tileId = tileSpec.getTileId();
            final LayoutData layout = tileSpec.getLayout();

            if (importData.splitSections) {
                final String sectionId = layout.getSectionId();
                final double z = Double.parseDouble(sectionId);
                trakLayer = importData.trakLayerSet.getLayer(z, thickness, true);
            } else {
                trakLayer = importData.trakLayerSet.getLayer(tileSpec.getZ(), thickness, true);
            }

            try {
                final List<ChannelSpec> allChannelSpecs = importData.channels.isEmpty() ? tileSpec.getAllChannels() :
                    tileSpec.getChannels(importData.channels);
                final ChannelSpec firstChannelSpec = allChannelSpecs.get(0);
                final ImageAndMask imageAndMask = firstChannelSpec.getFirstMipmapEntry().getValue();
                final String imageFilePath = imageAndMask.getImageFilePath();

                final int o_width = tileSpec.getWidth();
                final int o_height = tileSpec.getHeight();

                if (importData.imagePlusType == -1) {
                    final ImagePlus imagePlus = trakLoader.openImagePlus(imageFilePath);
                    type = imagePlus.getType();
                }

                final double minIntensity = firstChannelSpec.getMinIntensity();
                final double maxIntensity = firstChannelSpec.getMaxIntensity();

                final Patch trakPatch = new Patch(importData.trakProject, tileId, o_width, o_height, o_width, o_height, type,
                                                  1.0f, Color.yellow, false, minIntensity, maxIntensity,
                                                  new AffineTransform(1, 0, 0, 1, 0, 0),
                                                  imageFilePath);

                if (imageAndMask.hasMask()) {
                    final String maskFilePath = imageAndMask.getMaskFilePath();
                    if (importData.loadMasks) {
                        maskProcessor = maskPathToProcessor.get(maskFilePath);
                        if (maskProcessor == null) {
                            final ImagePlus maskImagePlus = trakLoader.openImagePlus(maskFilePath);
                            maskProcessor = maskImagePlus.getProcessor().convertToByteProcessor();
                            maskPathToProcessor.put(maskFilePath, maskProcessor);
                            Utils.log("convertTileSpecsToPatches: loaded mask " + maskFilePath);
                        }
                        trakPatch.setAlphaMask(maskProcessor);
                    } else {
                        skippedMasksCsvData.append(tileId).append(',').append(maskFilePath).append('\n');
                    }
                }

                final List<CoordinateTransform> transforms =
                        tileSpec.getTransforms().getNewInstanceAsList().getList(null);

                if (importData.replaceLastWithStage) {

                    final TranslationModel2D stageTransform = new TranslationModel2D();
                    stageTransform.set(layout.getStageX(), layout.getStageY());
                    transforms.set((transforms.size() - 1), stageTransform);

                    importData.updateMaxXAndY((layout.getStageX() + tileSpec.getWidth()),
                                              (layout.getStageY() + tileSpec.getHeight()));
                }

                for (final CoordinateTransform transform : transforms) {
                    if (transform instanceof mpicbg.trakem2.transform.CoordinateTransform) {
                        trakPatch.appendCoordinateTransform((mpicbg.trakem2.transform.CoordinateTransform) transform);
                    } else {
                        throw new IllegalArgumentException(
                                "tile " + tileId + " references unsupported transform class " +
                                transform.getClass().getName());
                    }

                }

                trakLoader.addedPatchFrom(imageFilePath, trakPatch);
                trakLayer.add(trakPatch);

                tileCount++;

                if (timer.hasIntervalPassed()) {
                    Utils.log("convertTileSpecsToPatches: converted " + tileCount +
                              " out of " + tileSpecList.size() + " tiles");
                }

            } catch (final Exception e) {
                System.out.println("\nfailed to convert tile " + tileId);
                e.printStackTrace(System.out);
            }
        }

        importData.trakLayerSet.setDimensions(0, 0, (float) importData.maxX, (float) importData.maxY);

        Utils.log("convertTileSpecsToPatches: converted " + tileCount +
                  " out of " + tileSpecList.size() + " tiles");

        if (tileCount < tileSpecList.size()) {
            Utils.log("\nWARNING: Check console window for details about tile conversion failures!\n");
        }

        if (skippedMasksCsvData.length() > 0) {
            final String storageFolder = importData.trakProject.getLoader().getStorageFolder();
            final String csvFileName = "skipped_masks_" + new Date().getTime() + ".csv";
            final Path csvPath = Paths.get(storageFolder, csvFileName);
            try {
                Files.write(csvPath, skippedMasksCsvData.toString().getBytes(), StandardOpenOption.CREATE);
                Utils.log("convertTileSpecsToPatches: saved skipped masks details in " + csvPath.toAbsolutePath());
            } catch (final IOException e) {
                System.out.println("\nfailed to save " + csvPath.toAbsolutePath());
                e.printStackTrace(System.out);
                Utils.log("convertTileSpecsToPatches: skippedMasksCsvData is:");
                Utils.log(skippedMasksCsvData.toString());
            }
        }

    }

    private static ImportData importData = null;

    private enum PluginArgument {
        NEW_PROJECT, IMPORT_LAYER;

        public static PluginArgument checkedValueOf(final String arg,
                                                    final PlugIn pluginInstance) {
            PluginArgument pluginArgument = null;
            try {
                pluginArgument = PluginArgument.valueOf(arg);
            } catch (final IllegalArgumentException e) {
                e.printStackTrace(System.out);
                IJ.error("Unsupported Plugin Argument",
                         "unsupported arg '" + arg + "' specified for " + pluginInstance.getClass().getName() + ", " +
                         "valid values are: " + Arrays.asList(PluginArgument.values()));
            }
            return pluginArgument;
        }

    }

    private class ImportData {

        private Project trakProject;
        private LayerSet trakLayerSet;

        private String baseDataUrl;
        private String renderOwner;
        private String renderProject;
        private String renderStack;
        private Set<String> channels;
        private double minZ;
        private double maxZ;
        private boolean splitSections;
        private boolean loadMasks;
        private boolean replaceLastWithStage;
        private int imagePlusType;
        private int numberOfMipmapThreads;

        private StackMetaData stackMetaData;
        private List<RenderParameters> layerRenderParametersList;
        private double maxX;
        private double maxY;

        ImportData(final Project trakProject,
                   final LayerSet trakLayerSet) {

            setTrakProjectAndLayerSet(trakProject, trakLayerSet);

            baseDataUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
            renderOwner = "flyTEM";
            renderProject = "FAFB00";
            renderStack = "v12_acquire_merged";
            channels = new HashSet<String>();
            minZ = 1.0;
            maxZ = minZ;
            imagePlusType = ImagePlus.GRAY8;
            splitSections = false;
            loadMasks = false;
            replaceLastWithStage = false;
            numberOfMipmapThreads = 1;
        }

        void setTrakProjectAndLayerSet(final Project trakProject,
                                       final LayerSet trakLayerSet) {
            this.trakProject = trakProject;
            this.trakLayerSet = trakLayerSet;
        }

        void updateMaxXAndY(final double maxX,
                            final double maxY) {
            this.maxX = Math.max(this.maxX, maxX);
            this.maxY = Math.max(this.maxY, maxY);
        }

        boolean setParametersFromDialog() {

            final GenericDialog dialog = new GenericDialog("Import Parameters");

            final int defaultTextColumns = 80;
            dialog.addStringField("Render Web Services Base URL", baseDataUrl, defaultTextColumns);
            dialog.addStringField("Render Stack Owner", renderOwner, defaultTextColumns);
            dialog.addStringField("Render Stack Project", renderProject, defaultTextColumns);
            dialog.addStringField("Render Stack Name", renderStack, defaultTextColumns);
            dialog.addStringField("Channel (empty for default)", "", defaultTextColumns);
            dialog.addNumericField("Min Z", minZ, 1);
            dialog.addNumericField("Max Z", maxZ, 1);
            dialog.addNumericField("Image Plus Type (use '-1' to slowly derive dynamically)", imagePlusType, 0);
            dialog.addMessage("  note: image plus type values are: 0:GRAY8, 1:GRAY16, 2:GRAY32, 3:COLOR_256, 4:COLOR_RGB");
            dialog.addCheckbox("Load Masks", loadMasks);
            dialog.addCheckbox("Split Sections", splitSections);
            dialog.addCheckbox("Replace Last Transform With Stage", replaceLastWithStage);
            dialog.addNumericField("Number of threads for mipmaps", numberOfMipmapThreads, 0);

            dialog.showDialog();
            dialog.repaint(); // seems to help with missing dialog elements, but shouldn't be necessary

            final boolean wasCancelled = dialog.wasCanceled();

            if (! wasCancelled) {
                baseDataUrl = dialog.getNextString();
                renderOwner = dialog.getNextString();
                renderProject = dialog.getNextString();
                renderStack =  dialog.getNextString();
                String channelString = dialog.getNextString();
                if (channelString != null && channelString.length() > 0) {
                    channels.add(channelString);
                }
                minZ = dialog.getNextNumber();
                maxZ = dialog.getNextNumber();
                imagePlusType = new Double(dialog.getNextNumber()).intValue();
                loadMasks = dialog.getNextBoolean();
                splitSections = dialog.getNextBoolean();
                replaceLastWithStage = dialog.getNextBoolean();
                numberOfMipmapThreads = (int) dialog.getNextNumber();
            }

            return wasCancelled;
        }

        boolean loadRenderData(final boolean isNewProject) {

            boolean wasCancelled = setParametersFromDialog();

            if (! wasCancelled) {

                try {

                    final StackId stackId = new StackId(renderOwner, renderProject, renderStack);
                    final RenderDataClient renderDataClient = new RenderDataClient(baseDataUrl,
                                                                                   renderOwner,
                                                                                   renderProject);

                    if (isNewProject) {
                        final String projectTitle = replaceLastWithStage ? renderStack + "_stage" : renderStack;
                        trakProject.setTitle(projectTitle);
                    }

                    stackMetaData = renderDataClient.getStackMetaData(stackId.getStack());

                    layerRenderParametersList = new ArrayList<>();
                    final Set<Double> zValues = new LinkedHashSet<>();
                    for (final SectionData sectionData : renderDataClient.getStackSectionData(renderStack,
                                                                                              minZ,
                                                                                              maxZ)) {
                        zValues.add(sectionData.getZ());
                    }

                    if (zValues.size() == 0) {
                        throw new IllegalArgumentException(
                                "The " + stackId + " does not contain any layers with z values between " +
                                minZ + " and " + maxZ);
                    } else {

                        for (final Double z : zValues) {
                            layerRenderParametersList.add(renderDataClient.getRenderParametersForZ(renderStack, z));
                            Utils.log("loadRenderData: loaded data for layer " + z + " in " + stackId);
                        }

                        if (isNewProject) {

                            final TileSpec firstTileSpec = layerRenderParametersList.get(0).getTileSpecs().get(0);
                            @SuppressWarnings("WrapperTypeMayBePrimitive")
                            final Double meshCellSize = firstTileSpec.getMeshCellSize();
                            trakProject.setProperty("mesh_resolution", String.valueOf(meshCellSize.intValue()));
                            trakProject.setProperty("n_mipmap_threads", String.valueOf(numberOfMipmapThreads));

                            if (replaceLastWithStage) {
                                final LayoutData layout = firstTileSpec.getLayout();
                                maxX = layout.getStageX() + firstTileSpec.getWidth();
                                maxY = layout.getStageY() + firstTileSpec.getHeight();
                            } else {
                                final Bounds stackBounds = stackMetaData.getStats().getStackBounds();
                                maxX = stackBounds.getMaxX();
                                maxY = stackBounds.getMaxY();
                            }
                        }

                    }

                } catch (final Exception e) {
                    e.printStackTrace(System.out);
                    IJ.error("Invalid Render Parameters",
                             "Specified parameters caused the following exception:\n" + e.getMessage());
                    wasCancelled = loadRenderData(isNewProject);
                }
            }

            return wasCancelled;
        }

    }

}
