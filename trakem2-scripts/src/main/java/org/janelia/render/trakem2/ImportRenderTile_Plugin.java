package org.janelia.render.trakem2;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;

import ini.trakem2.utils.Utils;

/**
 * This plug-in imports render web services stack tile data into a TrakEM project.
 *
 * @author Eric Trautman
 */
public class ImportRenderTile_Plugin
        implements PlugIn {

    private static RenderContextPanel.DefaultValues DEFAULT_VALUES = new RenderContextPanel.DefaultValues();

    @Override
    public void run(final String arg) {

        Utils.log("\norg.janelia.render.trakem2.ImportRenderTile_Plugin.run: entry");
        tileData = new TileData();
        final boolean wasCancelled = tileData.loadRenderData();
        if ((! wasCancelled) && (tileData.renderParameters != null)) {
            renderTileSpec(tileData.renderParameters);
        }
        Utils.log("\norg.janelia.render.trakem2.ImportRenderTile_Plugin.run: exit");

    }

    private void renderTileSpec(final RenderParameters renderParameters) {

        boolean renderLocally = tileData.renderLocallyIfPossible;

        if (tileData.renderLocallyIfPossible) {
            final TileSpec tileSpec = renderParameters.getTileSpecs().get(0);
            final ImageAndMask imageAndMask = tileSpec.getFirstMipmapEntry().getValue();
            try {
                imageAndMask.validate();
            } catch (final Exception e) {
                Utils.log("rendering tile " + tileSpec.getTileId() +
                          " remotely because its source data cannot be loaded locally: " + imageAndMask);
                renderLocally = false;
            }
        }

        final ImagePlus imagePlus;
        if (renderLocally) {

            final TransformMeshMappingWithMasks.ImageProcessorWithMasks
                    ipwm = Renderer.renderImageProcessorWithMasks(renderParameters,
                                                                  ImageProcessorCache.DISABLED_CACHE);
            imagePlus = new ImagePlus(tileData.tileId, ipwm.ip);

        } else {

            final String remoteTileUrl = tileData.tileUrlString + "/tiff-image?format=.tif";

            Utils.log("renderTileSpec: loading rendered tile from " + remoteTileUrl);

            imagePlus = new ImagePlus(remoteTileUrl);
            if (imagePlus.isProcessor()) {
                imagePlus.setTitle(tileData.tileId);
            } else {
                Utils.log("renderTileSpec: ERROR, failed to load rendered tile from " + remoteTileUrl);
            }

        }

        if (imagePlus.isProcessor())  {

            final Pattern p = Pattern.compile(".*/owner/(.*)/project/(.*)/stack/(.*)/tile/.*");
            final Matcher m = p.matcher(tileData.tileUrlString);
            if (m.matches()) {
                final String label = m.group(1) + "|" + m.group(2) + "|" + m.group(3);
                imagePlus.setProperty("Label", label);
                Utils.log("renderTileSpec: set label as " + label);
            }

            imagePlus.show();

        }

    }

    private static TileData tileData = null;

    private static class TileData {
        private String tileId;

        private String tileUrlString;

        private boolean renderLocallyIfPossible;

        private RenderParameters renderParameters;

        boolean setParametersFromDialog() {

            final GenericDialog dialog = new GenericDialog("Import Parameters");
            final RenderContextPanel renderContextPanel = new RenderContextPanel(true,
                                                                                 DEFAULT_VALUES);
            dialog.addPanel(renderContextPanel);
            dialog.addCheckbox("Render locally (if possible)", true);

            dialog.showDialog();
            dialog.repaint(); // seems to help with missing dialog elements, but shouldn't be necessary

            final boolean wasCancelled = dialog.wasCanceled();

            if (! wasCancelled) {
                tileId = renderContextPanel.getSelectedTileId();
                tileUrlString = renderContextPanel.getTileUrlString();
                renderLocallyIfPossible = dialog.getNextBoolean();
                DEFAULT_VALUES = renderContextPanel.buildDefaultValuesFromCurrentSelections();
            }

            return wasCancelled;
        }

        boolean loadRenderData() {

            boolean wasCancelled = setParametersFromDialog();

            if ((! wasCancelled) && (tileUrlString != null)) {

                try {
                    renderParameters = RenderParameters.loadFromUrl(tileUrlString + "/render-parameters");
                    renderParameters.initializeDerivedValues();
                } catch (final Exception e) {
                    e.printStackTrace(System.out);
                    IJ.error("Invalid Render Parameters",
                             "Specified parameters caused the following exception:\n" + e.getMessage());
                    wasCancelled = loadRenderData();
                }
            }

            return wasCancelled;
        }

    }

    public static void main(final String[] args) {
        new ImageJ();
        new ImportRenderTile_Plugin().run("");
    }
}
