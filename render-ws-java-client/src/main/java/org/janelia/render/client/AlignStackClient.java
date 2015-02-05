package org.janelia.render.client;

import mpicbg.trakem2.transform.AffineModel2D;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java client for generating Align stack data.
 *
 * @author Eric Trautman
 */
public class AlignStackClient {

    /**
     * @param  args  see {@link org.janelia.render.client.MLSStackClientParameters} for command line argument details.
     */
    public static void main(String[] args) {
        try {

            final AlignStackClientParameters params = AlignStackClientParameters.parseCommandLineArgs(args);

            if (params.displayHelp()) {

                params.showUsage();

            } else {

                LOG.info("main: entry, params={}", params);

                final AlignStackClient client = new AlignStackClient(params);

                client.generateStackData();
            }

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
        }
    }

    private final AlignStackClientParameters parameters;
    private final String acquireStack;
    private final String alignStack;
    private final String metFile;

    private final Map<String, TransformSpec> tileIdToAlignTransformMap;
    private final RenderDataClient renderDataClient;

    private String metSection;
    private String lastTileId;

    public AlignStackClient(final AlignStackClientParameters parameters) {

        this.parameters = parameters;
        this.alignStack = parameters.getAlignStack();
        this.acquireStack = parameters.getAcquireStack();
        this.metFile = parameters.getMetFile();

        final int capacityForLargeSection = (int) (5000 / 0.75);
        this.tileIdToAlignTransformMap = new HashMap<>(capacityForLargeSection);

        this.renderDataClient = new RenderDataClient(parameters.getBaseDataUrl(),
                                                     parameters.getOwner(),
                                                     parameters.getProject());

        this.metSection = null;
        this.lastTileId = null;
    }

    public void generateStackData() throws Exception {

        final int alignTileCount = tileIdToAlignTransformMap.size();

        LOG.info("generateStackData: entry, alignTileCount={}", alignTileCount);

        loadMetData();

        final TileSpec lastTileSpec = renderDataClient.getTile(acquireStack, lastTileId);
        final Double z = lastTileSpec.getZ();

        LOG.info("generateStackData: mapped section {} to z value {}", metSection, z);

        final ResolvedTileSpecCollection acquireTiles = renderDataClient.getResolvedTiles(acquireStack, z);

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

            if (parameters.isReplaceAll())  {

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

        renderDataClient.saveResolvedTiles(acquireTiles, alignStack, z);

        LOG.info("generateStackData: exit, saved tiles and transforms for {}", z);
    }

    @Override
    public String toString() {
        return "AlignStackClient{" +
               "renderDataClient=" + renderDataClient +
               ", acquireStack='" + acquireStack + '\'' +
               ", alignStack='" + alignStack + '\'' +
               ", metFile='" + metFile + '\'' +
               '}';
    }

    private void loadMetData()
            throws IOException, IllegalArgumentException {

        final Path path = FileSystems.getDefault().getPath(metFile).toAbsolutePath();

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

    private static final Logger LOG = LoggerFactory.getLogger(AlignStackClient.class);

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
}
