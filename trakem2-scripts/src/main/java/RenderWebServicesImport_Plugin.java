import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.models.CoordinateTransform;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
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
    private RenderParameters layerRenderParameters;

    private boolean splitSections;
    private boolean loadMasks;

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
        gd.addNumericField("Z", 2429.0, 1);
        gd.addCheckbox("Split Sections", true);
        gd.addCheckbox("Load Masks", false);
        gd.showDialog();

        if (! gd.wasCanceled()) {

            try {
                final String baseDataUrl = gd.getNextString();
                final StackId stackId = new StackId(gd.getNextString(), gd.getNextString(), gd.getNextString());
                final double z = gd.getNextNumber();
                splitSections = gd.getNextBoolean();
                loadMasks = gd.getNextBoolean();

                final RenderDataClient renderDataClient = new RenderDataClient(baseDataUrl, stackId.getOwner(), stackId.getProject());

                trakProject.setTitle(stackId.getStack());

                stackMetaData = renderDataClient.getStackMetaData(stackId.getStack());
                layerRenderParameters = renderDataClient.getRenderParametersForZ(stackId.getStack(), z);

                System.out.println("loadRenderData: loaded data for layer " + z + " in " + stackId + "\n");

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

        final StackStats stackStats = stackMetaData.getStats();
        if (stackStats != null) {
            final Bounds stackBounds = stackStats.getStackBounds();
            if (stackBounds != null) {
                trakLayerSet.setDimensions((float) layerRenderParameters.getX(),
                                           (float) layerRenderParameters.getY(),
                                           (float) layerRenderParameters.getWidth(),
                                           (float) layerRenderParameters.getHeight());
            }
        }

        final Loader trakLoader = trakProject.getLoader();
        Layer trakLayer;

        final Map<String, ByteProcessor> maskPathToProcessor =  new HashMap<>();
        ByteProcessor maskProcessor;

        final List<TileSpec> tileSpecList = layerRenderParameters.getTileSpecs();

        int tileCount = 0;
        for (final TileSpec tileSpec : tileSpecList) {

            final String tileId = tileSpec.getTileId();

            if (splitSections) {
                final String sectionId = tileSpec.getLayout().getSectionId();
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

                final ImagePlus imagePlus = trakLoader.openImagePlus(imageFilePath);
                final int o_width = imagePlus.getWidth();
                final int o_height = imagePlus.getHeight();
                final int type = imagePlus.getType();

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

        Utils.log("convertTileSpecsToPatches: converted " + tileCount +
                  " out of " + tileSpecList.size() + " tiles");

        if (tileCount < tileSpecList.size()) {
            Utils.log("\nWARNING: Check console window for details about tile conversion failures!\n");
        }

    }

    private static final String IMPORT_ERROR_TITLE = "Render Web Services Import Plugin Error";

}
