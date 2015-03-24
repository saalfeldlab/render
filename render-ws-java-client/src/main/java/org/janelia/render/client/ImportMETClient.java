package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating importing MET data from stitching and alignment processes into the render database.
 *
 * @author Eric Trautman
 */
public class ImportMETClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--acquireStack", description = "Name of source (acquire) stack containing base tile specifications", required = true)
        private String acquireStack;

        @Parameter(names = "--alignStack", description = "Name of target (align, montage, etc.) stack that will contain imported transforms", required = true)
        private String alignStack;

        @Parameter(names = "--metFile", description = "MET file for section", required = true)
        private String metFile;

        @Parameter(names = "--replaceAll", description = "Replace all transforms with the MET transform (default is to only replace the last transform)", required = false, arity = 0)
        private boolean replaceAll;
    }

    public static void main(final String[] args) {
        try {
            final Parameters parameters = new Parameters();
            parameters.parse(args);

            LOG.info("main: entry, parameters={}", parameters);

            final ImportMETClient client = new ImportMETClient(parameters);

            client.generateStackData();

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
            System.exit(1);
        }
    }

    private final Parameters parameters;

    private final Map<String, TransformSpec> tileIdToAlignTransformMap;
    private final RenderDataClient renderDataClient;

    private String metSection;
    private String lastTileId;

    public ImportMETClient(final Parameters parameters) {

        this.parameters = parameters;

        final int capacityForLargeSection = (int) (5000 / 0.75);
        this.tileIdToAlignTransformMap = new HashMap<>(capacityForLargeSection);

        this.renderDataClient = parameters.getClient();

        this.metSection = null;
        this.lastTileId = null;
    }

    public void generateStackData() throws Exception {

        final int alignTileCount = tileIdToAlignTransformMap.size();

        LOG.info("generateStackData: entry, alignTileCount={}", alignTileCount);

        loadMetData();

        final TileSpec lastTileSpec = renderDataClient.getTile(parameters.acquireStack, lastTileId);
        final Double z = lastTileSpec.getZ();

        LOG.info("generateStackData: mapped section {} to z value {}", metSection, z);

        final ResolvedTileSpecCollection acquireTiles = renderDataClient.getResolvedTiles(parameters.acquireStack, z);

        if (! acquireTiles.hasTileSpecs()) {
            throw new IllegalArgumentException(acquireTiles + " does not have any tiles");
        }

        LOG.info("generateStackData: filtering tile spec collection {}", acquireTiles);

        acquireTiles.filterSpecs(tileIdToAlignTransformMap.keySet());

        if (! acquireTiles.hasTileSpecs()) {
            throw new IllegalArgumentException("after filtering out non-aligned tiles, " +
                                               acquireTiles + " does not have any remaining tiles");
        }

        LOG.info("generateStackData: after filter, collection is {}", acquireTiles);

        final ProcessTimer timer = new ProcessTimer();
        int tileSpecCount = 0;
        TransformSpec alignTransform;
        TileSpec tileSpec;
        for (String tileId : tileIdToAlignTransformMap.keySet()) {
            alignTransform = tileIdToAlignTransformMap.get(tileId);

            if (parameters.replaceAll)  {

                tileSpec = acquireTiles.getTileSpec(tileId);

                if (tileSpec == null) {
                    throw new IllegalArgumentException("tile spec with id '" + tileId +
                                                       "' not found in " + acquireTiles +
                                                       ", possible issue with z value");
                }

                tileSpec.setTransforms(new ListTransformSpec());
            }

            acquireTiles.addTransformSpecToTile(tileId, alignTransform, true);
            tileSpecCount++;
            if (timer.hasIntervalPassed()) {
                LOG.info("generateStackData: updated transforms for {} out of {} tiles",
                         tileSpecCount, alignTileCount);
            }
        }

        LOG.debug("generateStackData: updated transforms for {} tiles, elapsedSeconds={}",
                  tileSpecCount, timer.getElapsedSeconds());

        renderDataClient.saveResolvedTiles(acquireTiles, parameters.alignStack, z);

        LOG.info("generateStackData: exit, saved tiles and transforms for {}", z);
    }

    private void loadMetData()
            throws IOException, IllegalArgumentException {

        final Path path = FileSystems.getDefault().getPath(parameters.metFile).toAbsolutePath();

        LOG.info("loadMetData: entry, path={}", path);

        final BufferedReader reader = Files.newBufferedReader(path, Charset.defaultCharset());

        // MET data format:
        //
        // section  tileId              ?  affineParameters (**NOTE: order 1-6 differs from renderer 1,4,2,5,3,6)
        // -------  ------------------  -  -------------------------------------------------------------------
        // 5100     140731162138009113  1  0.992264  0.226714  27606.648556  -0.085614  0.712238  38075.232380  9  113  0  /nobackup/flyTEM/data/whole_fly_1/141111-lens/140731162138_112x144/col0009/col0009_row0113_cam0.png  -999

        final int lastAffineParameter = 9;
        int lineNumber = 0;
        final AffineModel2D affineModel = new AffineModel2D();

        String line;
        String[] w;
        String section;
        String tileId = null;
        String affineData;
        while ((line = reader.readLine()) != null) {

            lineNumber++;

            w = WHITESPACE_PATTERN.split(line);

            if (w.length < lastAffineParameter) {

                LOG.warn("loadMetData: skipping line {} because it only contains {} words", lineNumber, w.length);

            } else {

                section = w[0];
                if (metSection == null) {
                    metSection = section;
                } else if (! metSection.equals(section)) {
                    throw new IllegalArgumentException("Differing sections (" + metSection + " and " + section +
                                                       ") included in MET file " + path +
                                                       ".  First difference found at line " + lineNumber + ".");
                }
                tileId = w[1];
                affineData = w[3] + ' ' + w[6] + ' ' + w[4] + ' ' + w[7] + ' ' + w[5] + ' ' + w[8];
                try {
                    affineModel.init(affineData);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Failed to parse affine data from line " + lineNumber +
                                                       " of MET file " + path + ".  Invalid data string is '" +
                                                       affineData + "'.", e);
                }

                if (tileIdToAlignTransformMap.containsKey(tileId)) {
                    throw new IllegalArgumentException("Tile ID " + tileId + " is listed more than once in MET file " +
                                                       path + ".  The second reference was found at line " +
                                                       lineNumber + ".");
                }

                tileIdToAlignTransformMap.put(tileId,
                                              new LeafTransformSpec(AffineModel2D.class.getName(), affineData));
            }

        }

        if (tileIdToAlignTransformMap.size() == 0) {
            throw new IllegalArgumentException("No tile information found in MET file " + path + ".");
        }

        lastTileId = tileId;

        LOG.info("loadMetData: exit, loaded {} tiles from {} lines for section {}",
                 tileIdToAlignTransformMap.size(), lineNumber, metSection);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportMETClient.class);

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
}
