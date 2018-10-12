import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.CoordinateTransform;
import mpicbg.trakem2.transform.TranslationModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.RenderDataClient;

import ini.trakem2.Project;
import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Utils;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class RenderWebServicesImport_Plugin
        implements PlugIn {

    private static ImportData importData;

    @Override
    public void run(final String arg) {

        System.out.println(">>>> RenderWebServicesImport_Plugin.run: entry >>>>");

        PluginArgument pluginArgument = null;
        try {
            pluginArgument = PluginArgument.valueOf(arg);
        } catch (final IllegalArgumentException e) {
            IJ.error("Unsupported Plugin Argument",
                     "unsupported arg '" + arg + "' specified for " + this.getClass().getName() + ", " +
                     "valid values are: " + Arrays.asList(PluginArgument.values()));
        }

        if (pluginArgument != null) {

            final List<Project> projectList = Project.getProjects();

            final boolean isNewProject = PluginArgument.NEW_PROJECT.equals(pluginArgument) ||
                                         (projectList.size() == 0);

            final boolean isImportLayer = PluginArgument.IMPORT_LAYER.equals(pluginArgument);


            if (isNewProject) {

                // need to set this to see dialog title for storage folder on Mac
                Prefs.useJFileChooser = true;

                final DirectoryChooser chooser = new DirectoryChooser("Select storage folder");
                String storageFolder = chooser.getDirectory();
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
        }

        System.out.println("<<<< RenderWebServicesImport_Plugin.run: exit <<<<");
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

        double layerSetMinX = Double.MAX_VALUE;
        double layerSetMinY = Double.MAX_VALUE;
        double layerSetMaxX = Double.MIN_VALUE;
        double layerSetMaxY = Double.MIN_VALUE;
        final List<TileSpec> tileSpecList = new ArrayList<>();
        for (final RenderParameters layerRenderParameters : importData.layerRenderParametersList) {
            tileSpecList.addAll(layerRenderParameters.getTileSpecs());
            if (! importData.replaceLastWithStage) {
                layerSetMinX = Math.min(layerSetMinX, layerRenderParameters.getX());
                layerSetMinY = Math.min(layerSetMinY, layerRenderParameters.getY());
                layerSetMaxX = Math.max(layerSetMaxX, layerRenderParameters.getX() + layerRenderParameters.getWidth());
                layerSetMaxY = Math.max(layerSetMaxY, layerRenderParameters.getX() + layerRenderParameters.getHeight());
            }
        }

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
                final List<ChannelSpec> allChannelSpecs = tileSpec.getAllChannels();
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
                            System.out.println("convertTileSpecsToPatches: loaded mask " + maskFilePath);
                        }
                        trakPatch.setAlphaMask(maskProcessor);
                    } else {
                        Utils.log("convertTileSpecsToPatches: skipped load of mask for patch, " + tileId + ", " +
                                  maskFilePath);
                    }
                }

                final List<CoordinateTransform> transforms =
                        tileSpec.getTransforms().getNewInstanceAsList().getList(null);

                if (importData.replaceLastWithStage) {

                    final TranslationModel2D stageTransform = new TranslationModel2D();
                    stageTransform.set(layout.getStageX(), layout.getStageY());
                    transforms.set((transforms.size() - 1), stageTransform);

                    layerSetMinX = Math.min(layerSetMinX, layout.getStageX());
                    layerSetMinY = Math.min(layerSetMinY, layout.getStageY());
                    layerSetMaxX = Math.max(layerSetMaxX, (layout.getStageX() + tileSpec.getWidth()));
                    layerSetMaxY = Math.max(layerSetMaxY, (layout.getStageY() + tileSpec.getHeight()));
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

                if (tileCount % 200 == 0) {
                    System.out.println("convertTileSpecsToPatches: converted " + tileCount + " tiles ...");
                }

            } catch (final Exception e) {
                System.out.println("\nfailed to convert tile " + tileId);
                e.printStackTrace(System.out);
            }
        }

        importData.trakLayerSet.setDimensions((float) layerSetMinX,
                                              (float) layerSetMinY,
                                              (float) (layerSetMaxX - layerSetMinX),
                                              (float) (layerSetMaxY - layerSetMinY));

        Utils.log("convertTileSpecsToPatches: converted " + tileCount +
                  " out of " + tileSpecList.size() + " tiles");

        if (tileCount < tileSpecList.size()) {
            Utils.log("\nWARNING: Check console window for details about tile conversion failures!\n");
        }

    }

    private enum PluginArgument {
        NEW_PROJECT, IMPORT_LAYER
    }

    private class ImportData {

        private Project trakProject;
        private LayerSet trakLayerSet;

        private String baseDataUrl;
        private String renderOwner;
        private String renderProject;
        private String renderStack;
        private double minZ;
        private double maxZ;
        private boolean splitSections;
        private boolean loadMasks;
        private boolean replaceLastWithStage;
        private int imagePlusType;
        private int numberOfMipmapThreads;

        private StackMetaData stackMetaData;
        private List<RenderParameters> layerRenderParametersList;

        ImportData(final Project trakProject,
                   final LayerSet trakLayerSet) {

            setTrakProjectAndLayerSet(trakProject, trakLayerSet);

            baseDataUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
            renderOwner = "flyTEM";
            renderProject = "FAFB00";
            renderStack = "v12_acquire_merged";
            minZ = 2429.0;
            maxZ = minZ;
            imagePlusType = ImagePlus.GRAY8;
            splitSections = true;
            loadMasks = false;
            replaceLastWithStage = false;
            numberOfMipmapThreads = 1;
        }

        void setTrakProjectAndLayerSet(final Project trakProject,
                                       final LayerSet trakLayerSet) {
            this.trakProject = trakProject;
            this.trakLayerSet = trakLayerSet;
        }

        boolean setParametersFromDialog() {

            final GenericDialog dialog = new GenericDialog("Render Stack");

            final int defaultTextColumns = 80;
            dialog.addStringField("Render Web Services Base URL", baseDataUrl, defaultTextColumns);
            dialog.addStringField("Render Stack Owner", renderOwner, defaultTextColumns);
            dialog.addStringField("Render Stack Project", renderProject, defaultTextColumns);
            dialog.addStringField("Render Stack Name", renderStack, defaultTextColumns);
            dialog.addNumericField("Min Z", minZ, 1);
            dialog.addNumericField("Max Z", maxZ, 1);
            dialog.addNumericField("Image Plus Type (use -1 to slowly derive dynamically)", imagePlusType, 0);
            dialog.addMessage("  note: image plus type values are: 0:GRAY8, 1:GRAY16, 2:GRAY32, 3:COLOR_256, 4:COLOR_RGB");
            dialog.addCheckbox("Split Sections", splitSections);
            dialog.addCheckbox("Load Masks", loadMasks);
            dialog.addCheckbox("Replace Last Transform With Stage", replaceLastWithStage);
            dialog.addNumericField("Number of threads for mipmaps", numberOfMipmapThreads, 0);

            dialog.showDialog();

            final boolean wasCancelled = dialog.wasCanceled();

            if (! wasCancelled) {
                baseDataUrl = dialog.getNextString();
                renderOwner = dialog.getNextString();
                renderProject = dialog.getNextString();
                renderStack =  dialog.getNextString();
                minZ = dialog.getNextNumber();
                maxZ = dialog.getNextNumber();
                imagePlusType = new Double(dialog.getNextNumber()).intValue();
                splitSections = dialog.getNextBoolean();
                loadMasks = dialog.getNextBoolean();
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
                            System.out.println("loadRenderData: loaded data for layer " + z + " in " + stackId + "\n");
                        }

                        if (isNewProject) {
                            final TileSpec firstTileSpec = layerRenderParametersList.get(0).getTileSpecs().get(0);
                            final Double meshCellSize = firstTileSpec.getMeshCellSize();
                            trakProject.setProperty("mesh_resolution", String.valueOf(meshCellSize.intValue()));
                            trakProject.setProperty("n_mipmap_threads", String.valueOf(numberOfMipmapThreads));
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
