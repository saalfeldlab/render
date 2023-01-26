package org.janelia.render.client.tile;

import ij.ImageJ;
import ij.ImagePlus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.RenderWebServiceParameters;

/**
 * Utility to render tiles locally for debugging issues.
 *
 * @author Eric Trautman
 */
public class RenderTilesTest {

    public static void main(final String[] args) {

        try {
            final RenderTileWithTransformsClient.Parameters parameters = new RenderTileWithTransformsClient.Parameters();

            // TODO: mount /nrs/fibsem to access align h5 data

            parameters.renderWeb = new RenderWebServiceParameters();
            parameters.renderWeb.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
            parameters.renderWeb.owner = "fibsem";
            parameters.renderWeb.project = "Z0422_17_VNC_1";
            parameters.stack = "v4_acquire_trimmed_align";
            parameters.renderWithoutMask = true; // exclude masks since we want the raw-raw image

            // TODO: set z bounds, row, and column for tiles of interest
            //   see http://renderer.int.janelia.org:8080/ng/#!%7B%22dimensions%22:%7B%22x%22:%5B8e-9%2C%22m%22%5D%2C%22y%22:%5B8e-9%2C%22m%22%5D%2C%22z%22:%5B8e-9%2C%22m%22%5D%7D%2C%22position%22:%5B26771.75%2C9907.5%2C28131.9765625%5D%2C%22crossSectionScale%22:128%2C%22projectionScale%22:65536%2C%22layers%22:%5B%7B%22type%22:%22image%22%2C%22source%22:%22n5://http://renderer.int.janelia.org:8080/n5_sources/fibsem/Z0422_17_VNC_1.n5/render/Z0422_17_VNC_1/v4_acquire_trimmed_align___20221108_150533%22%2C%22tab%22:%22source%22%2C%22name%22:%22Z0422_17_VNC_1%20v4_acquire_trimmed_align%22%7D%5D%2C%22selectedLayer%22:%7B%22layer%22:%22Z0422_17_VNC_1%20v4_acquire_trimmed_align%22%7D%2C%22layout%22:%224panel%22%7D
            final Double minZ = 28132.0;
            final Double maxZ = 28132.0;
            final int row = 0;
            final int column = 2;

            // TODO: set to true to include scan correction, set to false for completely raw tile
            final boolean includeScanCorrectionTransforms = false;

            // TODO: downscale if you like
            final double renderScale = 1.0;

            // TODO: set this to null to view them interactively or to an existing directory to save tiles
            final File savedTileDirectory = new File("/Users/trautmane/Desktop/stern/streak_fix/rendered_images");

            // TODO: change to another format if you are saving files and don't want pngs
            parameters.format = Utils.TIF_FORMAT;

            renderTiles(parameters,
                        minZ,
                        maxZ,
                        row,
                        column,
                        includeScanCorrectionTransforms,
                        renderScale,
                        savedTileDirectory);

        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    // you should not need to change anything in here ...
    @SuppressWarnings("SameParameterValue")
    private static void renderTiles(final RenderTileWithTransformsClient.Parameters parameters,
                                    final Double minZ,
                                    final Double maxZ,
                                    final int row,
                                    final int column,
                                    final boolean includeScanCorrectionTransforms,
                                    final double renderScale,
                                    final File savedTileDirectory)
            throws IOException {

        final RenderDataClient dataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                 parameters.renderWeb.owner,
                                                                 parameters.renderWeb.project);

        final ResolvedTileSpecCollection resolvedTileSpecs = dataClient.getResolvedTiles(parameters.stack,
                                                                                         minZ,
                                                                                         maxZ,
                                                                                         null,
                                                                                         null,
                                                                                         null,
                                                                                         null,
                                                                                         null);
        resolvedTileSpecs.resolveTileSpecs();

        final RenderTileWithTransformsClient client = new RenderTileWithTransformsClient(parameters);

        if (savedTileDirectory == null) {
            System.getProperties().setProperty("plugins.dir", "/Applications/Fiji.app/plugins");
            new ImageJ();
        } else {
            FileUtil.ensureWritableDirectory(savedTileDirectory);
        }

        for (final TileSpec tileSpec : resolvedTileSpecs.getTileSpecs()) {

            final String tileId = tileSpec.getTileId();

            final Matcher m = TILE_ID_PATTERN.matcher(tileId);
            if (m.matches()) {
                final int tileRow = Integer.parseInt(m.group(1));
                final int tileColumn = Integer.parseInt(m.group(2));

                if ((row == tileRow) && (column == tileColumn)) {
                    final List<TransformSpec> transformSpecs =
                            includeScanCorrectionTransforms ?
                            tileSpec.getTransforms().getMatchSpecList().toUtilList() : new ArrayList<>();

                    final File savedTileFile =
                            savedTileDirectory == null ? null : new File(savedTileDirectory,
                                                                         tileId + "." + parameters.format);
                    final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                            client.renderTile(tileSpec,
                                              transformSpecs,
                                              renderScale,
                                              null,
                                              savedTileFile);

                    if (savedTileDirectory == null) {
                        new ImagePlus(tileId, ipwm.ip).show();
                    }
                }
            }
        }
    }

    private static final Pattern TILE_ID_PATTERN = Pattern.compile(".*_0-(\\d)-(\\d)\\.(?:patch\\.)?(\\d++)\\.0");

}
