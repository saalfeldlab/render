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

    private Project trakProject;
    private LayerSet trakLayerSet;

    private StackMetaData stackMetaData;
    private List<RenderParameters> layerRenderParametersList;

    private boolean splitSections;
    private boolean loadMasks;
    private boolean replaceLastWithStage;
    private int imageType;

    @Override
    public void run(final String arg) {

        System.out.println(">>>> RenderWebServicesImport_Plugin.run: entry >>>>");

        // need to set this to see dialog title for storage folder on Mac
        Prefs.useJFileChooser = true;

        final DirectoryChooser chooser = new DirectoryChooser("Select storage folder");
        String storageFolder = chooser.getDirectory();
        if (storageFolder != null) {

            storageFolder = storageFolder.replace('\\', '/');

            trakProject = Project.newFSProject("blank", null, storageFolder, false);
            trakLayerSet = trakProject.getRootLayerSet();

            if ("loadLayer".equals(arg)) {
                loadLayer();
            } else {
                // TODO: dynamically build supported task list if this plugin ever supports more than one task
                IJ.error(IMPORT_ERROR_TITLE,
                         "unsupported render web services import task '" + arg + "' specified, " +
                         "valid options are: ['loadLayer']");
            }
        }

        System.out.println("<<<< RenderWebServicesImport_Plugin.run: exit <<<<");
    }

    private void loadLayer() {
        if (loadRenderData()) {
            convertTileSpecsToPatches();
        }
    }

    private boolean loadRenderData() {

        boolean isDataLoaded = false;

        final int defaultTextColumns = 80;
        final GenericDialog gd = new GenericDialog("Render Stack");
        gd.addStringField("Render Web Services Base URL", "http://tem-services.int.janelia.org:8080/render-ws/v1", defaultTextColumns);
        gd.addStringField("Render Stack Owner", "flyTEM", defaultTextColumns);
        gd.addStringField("Render Stack Project", "FAFB00", defaultTextColumns);
        gd.addStringField("Render Stack Name", "v12_acquire_merged", defaultTextColumns);
        gd.addNumericField("Min Z", 2429.0, 1);
        gd.addNumericField("Max Z", 2429.0, 1);
        gd.addNumericField("Image Type (use -1 to dynamically derive)", 0, 0);
        gd.addCheckbox("Split Sections", true);
        gd.addCheckbox("Load Masks", false);
        gd.addCheckbox("Replace Last Transform With Stage", false);
        gd.showDialog();

        if (! gd.wasCanceled()) {

            try {
                final String baseDataUrl = gd.getNextString();
                final StackId stackId = new StackId(gd.getNextString(), gd.getNextString(), gd.getNextString());
                final double minZ = gd.getNextNumber();
                final double maxZ = gd.getNextNumber();
                imageType = new Double(gd.getNextNumber()).intValue();
                splitSections = gd.getNextBoolean();
                loadMasks = gd.getNextBoolean();
                replaceLastWithStage = gd.getNextBoolean();

                final RenderDataClient renderDataClient = new RenderDataClient(baseDataUrl, stackId.getOwner(), stackId.getProject());

                final String stack = stackId.getStack();

                final String projectTitle = replaceLastWithStage ? stack + "_stage" : stack;
                trakProject.setTitle(projectTitle);

                stackMetaData = renderDataClient.getStackMetaData(stackId.getStack());

                layerRenderParametersList = new ArrayList<>();
                final Set<Double> zValues = new LinkedHashSet<>();
                for (final SectionData sectionData : renderDataClient.getStackSectionData(stack,
                                                                                          minZ,
                                                                                          maxZ)) {
                    zValues.add(sectionData.getZ());
                }

                for (final Double z : zValues) {
                    layerRenderParametersList.add(renderDataClient.getRenderParametersForZ(stack, z));
                    System.out.println("loadRenderData: loaded data for layer " + z + " in " + stackId + "\n");
                }

                isDataLoaded = true;
            } catch (final Exception e) {
                e.printStackTrace(System.out);
                IJ.error(IMPORT_ERROR_TITLE,
                         "Failed to retrieve data from render web services.\n" +
                         "Cause: " + e.getMessage());
            }

        }

        return isDataLoaded;
    }

    private void convertTileSpecsToPatches() {

        double thickness = 35.0;
        final StackVersion stackVersion = stackMetaData.getCurrentVersion();
        if (stackVersion != null) {
            final Double resolutionZ = stackVersion.getStackResolutionZ();
            if (resolutionZ != null) {
                thickness = resolutionZ;
            }
        }

        final Loader trakLoader = trakProject.getLoader();
        Layer trakLayer;

        final Map<String, ByteProcessor> maskPathToProcessor =  new HashMap<>();
        ByteProcessor maskProcessor;

        double layerSetMinX = Double.MAX_VALUE;
        double layerSetMinY = Double.MAX_VALUE;
        double layerSetMaxX = Double.MIN_VALUE;
        double layerSetMaxY = Double.MIN_VALUE;
        final List<TileSpec> tileSpecList = new ArrayList<>();
        for (final RenderParameters layerRenderParameters : layerRenderParametersList) {
            tileSpecList.addAll(layerRenderParameters.getTileSpecs());
            if (! replaceLastWithStage) {
                layerSetMinX = Math.min(layerSetMinX, layerRenderParameters.getX());
                layerSetMinY = Math.min(layerSetMinY, layerRenderParameters.getY());
                layerSetMaxX = Math.max(layerSetMaxX, layerRenderParameters.getX() + layerRenderParameters.getWidth());
                layerSetMaxY = Math.max(layerSetMaxY, layerRenderParameters.getX() + layerRenderParameters.getHeight());
            }
        }

        int tileCount = 0;
        int type = imageType;
        for (final TileSpec tileSpec : tileSpecList) {

            final String tileId = tileSpec.getTileId();
            final LayoutData layout = tileSpec.getLayout();

            if (splitSections) {
                final String sectionId = layout.getSectionId();
                final double z = Double.parseDouble(sectionId);
                trakLayer = trakLayerSet.getLayer(z, thickness, true);
            } else {
                trakLayer = trakLayerSet.getLayer(tileSpec.getZ(), thickness, true);
            }

            try {
                final List<ChannelSpec> allChannelSpecs = tileSpec.getAllChannels();
                final ChannelSpec firstChannelSpec = allChannelSpecs.get(0);
                final ImageAndMask imageAndMask = firstChannelSpec.getFirstMipmapEntry().getValue();
                final String imageFilePath = imageAndMask.getImageFilePath();

                final int o_width = tileSpec.getWidth();
                final int o_height = tileSpec.getHeight();

                if (imageType == -1) {
                    final ImagePlus imagePlus = trakLoader.openImagePlus(imageFilePath);
                    type = imagePlus.getType();
                }

                final double minIntensity = firstChannelSpec.getMinIntensity();
                final double maxIntensity = firstChannelSpec.getMaxIntensity();

                final Patch trakPatch = new Patch(trakProject, tileId, o_width, o_height, o_width, o_height, type,
                                                  1.0f, Color.yellow, false, minIntensity, maxIntensity,
                                                  new AffineTransform(1, 0, 0, 1, 0, 0),
                                                  imageFilePath);

                if (imageAndMask.hasMask()) {
                    final String maskFilePath = imageAndMask.getMaskFilePath();
                    if (loadMasks) {
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

                if (replaceLastWithStage) {

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

        trakLayerSet.setDimensions((float) layerSetMinX, (float) layerSetMinY, (float) (layerSetMaxX - layerSetMinX), (float) (layerSetMaxY - layerSetMinY));

        Utils.log("convertTileSpecsToPatches: converted " + tileCount +
                  " out of " + tileSpecList.size() + " tiles");

        if (tileCount < tileSpecList.size()) {
            Utils.log("\nWARNING: Check console window for details about tile conversion failures!\n");
        }

    }

    private static final String IMPORT_ERROR_TITLE = "Render Web Services Import Plugin Error";

}
