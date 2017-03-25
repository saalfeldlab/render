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
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.PolynomialTransform2D;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TemTileSpecValidator;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.ImportTransformChangesClient.ChangeMode;
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

        @Parameter(names = "--stack", description = "Name of source stack containing base tile specifications", required = true)
        private String stack;

        @Parameter(
                names = "--targetOwner",
                description = "Name of owner for target stack that will contain imported transforms (default is to reuse source owner)",
                required = false)
        private String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of project for target stack that will contain imported transforms (default is to reuse source project)",
                required = false)
        private String targetProject;

        @Parameter(names = "--targetStack", description = "Name of target (align, montage, etc.) stack that will contain imported transforms", required = true)
        private String targetStack;

        @Parameter(names = "--metFile", description = "MET file for section", required = true)
        private String metFile;

        @Parameter(names = "--formatVersion", description = "MET format version ('v1', v2', 'v3', ...)", required = false)
        private String formatVersion = "v1";

        @Parameter(
                names = "--changeMode",
                description = "Specifies how the transforms should be applied to existing data",
                required = false)
        private ChangeMode changeMode = ChangeMode.REPLACE_LAST;

        @Parameter(names = "--disableValidation", description = "Disable flyTEM tile validation", required = false, arity = 0)
        private boolean disableValidation;

        public String getTargetOwner() {
            if (targetOwner == null) {
                targetOwner = owner;
            }
            return targetOwner;
        }

        public String getTargetProject() {
            if (targetProject == null) {
                targetProject = project;
            }
            return targetProject;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, ImportMETClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ImportMETClient client = new ImportMETClient(parameters);

                client.generateStackData();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;

    private final Map<String, SectionData> metSectionToDataMap;

    public ImportMETClient(final Parameters parameters) {
        this.parameters = parameters;

        if (parameters.disableValidation) {
            this.tileSpecValidator = null;
        } else {
            this.tileSpecValidator = new TemTileSpecValidator();
        }

        this.sourceRenderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                           parameters.owner,
                                                           parameters.project);

        this.targetRenderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                           parameters.getTargetOwner(),
                                                           parameters.getTargetProject());

        this.metSectionToDataMap = new HashMap<>();
    }

    public void generateStackData() throws Exception {

        LOG.info("generateStackData: entry");

        final StackMetaData sourceStackMetaData = sourceRenderDataClient.getStackMetaData(parameters.stack);
        targetRenderDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);

        if ("v2".equalsIgnoreCase(parameters.formatVersion) || "v3".equalsIgnoreCase(parameters.formatVersion)) {
            loadMetData();
        } else {

            // MET v1 data format:
            //
            // section  tileId              ?  affineParameters (**NOTE: order 1-6 differs from renderer 1,4,2,5,3,6)
            // -------  ------------------  -  -------------------------------------------------------------------
            // 5100     140731162138009113  1  0.992264  0.226714  27606.648556  -0.085614  0.712238  38075.232380  9  113  0  /nobackup/flyTEM/data/whole_fly_1/141111-lens/140731162138_112x144/col0009/col0009_row0113_cam0.png  -999

            final int[] parameterIndexes = {3, 6, 4, 7, 5, 8};
            final int lastAffineParameter = 9;
            loadV1MetData(parameterIndexes, lastAffineParameter, new AffineModel2D());
            
        }

        for (final SectionData sectionData : metSectionToDataMap.values()) {
            sectionData.updateTiles();
        }

        // only save updated data if all updates completed successfully
        for (final SectionData sectionData : metSectionToDataMap.values()) {
            sectionData.saveTiles();
        }

        LOG.info("generateStackData: exit, saved tiles and transforms for {}", metSectionToDataMap.values());
    }

    // TODO: remove old MET loader once Khaled no longer needs it
    private void loadV1MetData(final int[] parameterIndexes,
                               final int lastParameterIndex,
                               final CoordinateTransform transformModel)
            throws IOException, IllegalArgumentException {

        final Path path = FileSystems.getDefault().getPath(parameters.metFile).toAbsolutePath();
        final String modelClassName = transformModel.getClass().getName();

        LOG.info("loadV1MetData: entry, formatVersion={}, modelClassName={}, path={}",
                 parameters.formatVersion, modelClassName, path);

        final BufferedReader reader = Files.newBufferedReader(path, Charset.defaultCharset());

        int lineNumber = 0;

        String line;
        String[] w;
        String section;
        String tileId;
        SectionData sectionData;
        final StringBuilder modelData = new StringBuilder(128);
        String modelDataString;
        while ((line = reader.readLine()) != null) {

            lineNumber++;

            w = WHITESPACE_PATTERN.split(line);

            if (w.length < lastParameterIndex) {

                LOG.warn("loadV1MetData: skipping line {} because it only contains {} words", lineNumber, w.length);

            } else {

                section = w[0];
                tileId = w[1];

                sectionData = metSectionToDataMap.get(section);

                if (sectionData == null) {
                    final TileSpec sourceTileSpec = sourceRenderDataClient.getTile(parameters.stack, tileId);
                    final Double z = sourceTileSpec.getZ();
                    LOG.info("loadV1MetData: mapped section {} to z value {} using tile {}", section, z, tileId);
                    sectionData = new SectionData(path, z, tileSpecValidator);
                    metSectionToDataMap.put(section, sectionData);
                }

                modelData.setLength(0);
                for (int i = 0; i < parameterIndexes.length; i++) {
                    if (i > 0) {
                        modelData.append(' ');
                    }
                    modelData.append(w[parameterIndexes[i]]);
                }
                modelDataString = modelData.toString();

                try {
                    transformModel.init(modelDataString);
                } catch (final Exception e) {
                    throw new IllegalArgumentException("Failed to parse transform data from line " + lineNumber +
                                                       " of MET file " + path + ".  Invalid data string is '" +
                                                       modelDataString + "'.", e);
                }

                sectionData.addTileId(tileId, lineNumber, modelClassName, modelDataString);
            }

        }

        if (metSectionToDataMap.size() == 0) {
            throw new IllegalArgumentException("No tile information found in MET file " + path + ".");
        }

        LOG.info("loadV1MetData: exit, loaded {} lines for {}",
                 lineNumber, metSectionToDataMap.values());
    }

    private void loadMetData()
            throws IOException, IllegalArgumentException {

        final Path path = FileSystems.getDefault().getPath(parameters.metFile).toAbsolutePath();

        final CoordinateTransform affineModel = new AffineModel2D();
        final CoordinateTransform polyModel = new PolynomialTransform2D();

        LOG.info("loadMetData: entry, formatVersion={}, path={}", parameters.formatVersion, path);

        int lineNumber = 0;

        try (final BufferedReader reader = Files.newBufferedReader(path, Charset.defaultCharset())) {

            String line;
            String[] w;
            String section;
            String tileId;
            int parameterCount;
            final int firstParameterIndex = 3;
            int lastParameterIndex;
            CoordinateTransform transformModel;
            SectionData sectionData;
            final StringBuilder modelData = new StringBuilder(128);
            String modelDataString;

            while ((line = reader.readLine()) != null) {

                lineNumber++;

                w = WHITESPACE_PATTERN.split(line);

                if (w.length < firstParameterIndex) {

                    LOG.warn("loadMetData: skipping line {} because it only contains {} words", lineNumber, w.length);

                } else {

                    section = w[0];
                    tileId = w[1];
                    try {
                        parameterCount = Integer.parseInt(w[2]);
                        lastParameterIndex = firstParameterIndex + parameterCount;
                    } catch (final NumberFormatException e) {
                        throw new IllegalArgumentException("Failed to parse parameter count from line " + lineNumber +
                                                           " of MET file " + path + ".  Invalid value is '" +
                                                           w[2] + "'.", e);
                    }

                    if (parameterCount < 6) {
                        throw new IllegalArgumentException("Failed to parse parameters from line " + lineNumber +
                                                           " of MET file " + path + ".  Parameter count value '" +
                                                           parameterCount + "' must be greater than 5.");
                    }

                    if (w.length < lastParameterIndex) {
                        throw new IllegalArgumentException("Failed to parse parameters from line " + lineNumber +
                                                           " of MET file " + path + ".  Expected " + parameterCount +
                                                           " parameters starting at word " + (firstParameterIndex + 1) +
                                                           " but line only contains " + w.length + " words.");
                    }

                    if (parameterCount > 6) {
                        transformModel = polyModel;
                    } else {
                        transformModel = affineModel;
                    }

                    sectionData = metSectionToDataMap.get(section);

                    if (sectionData == null) {
                        final TileSpec sourceTileSpec = sourceRenderDataClient.getTile(parameters.stack, tileId);
                        final Double z = sourceTileSpec.getZ();
                        LOG.info("loadMetData: mapped section {} to z value {} using tile {}", section, z, tileId);
                        sectionData = new SectionData(path, z, tileSpecValidator);
                        metSectionToDataMap.put(section, sectionData);
                    }

                    modelData.setLength(0);
                    for (int i = firstParameterIndex; i < lastParameterIndex; i++) {
                        if (i > firstParameterIndex) {
                            modelData.append(' ');
                        }
                        modelData.append(w[i]);
                    }
                    modelDataString = modelData.toString();

                    try {
                        transformModel.init(modelDataString);
                    } catch (final Exception e) {
                        throw new IllegalArgumentException("Failed to parse transform data from line " + lineNumber +
                                                           " of MET file " + path + ".  Invalid data string is '" +
                                                           modelDataString + "'.", e);
                    }

                    sectionData.addTileId(tileId, lineNumber, transformModel.getClass().getName(), modelDataString);
                }

            }
        }

        if (metSectionToDataMap.size() == 0) {
            throw new IllegalArgumentException("No tile information found in MET file " + path + ".");
        }

        LOG.info("loadMetData: exit, loaded {} lines for {}",
                 lineNumber, metSectionToDataMap.values());
    }

    private class SectionData {

        private final Path path;
        private final Double z;
        private final TileSpecValidator tileSpecValidator;
        private final Map<String, TransformSpec> tileIdToAlignTransformMap;
        private ResolvedTileSpecCollection updatedTiles;

        private SectionData(final Path path,
                            final Double z,
                            final TileSpecValidator tileSpecValidator) {
            this.path = path;
            this.z = z;
            this.tileSpecValidator = tileSpecValidator;
            final int capacityForLargeSection = (int) (5000 / 0.75);
            this.tileIdToAlignTransformMap = new HashMap<>(capacityForLargeSection);
            this.updatedTiles = null;
        }

        public int getUpdatedTileCount() {
            return updatedTiles == null ? 0 : updatedTiles.getTileCount();
        }

        @Override
        public String toString() {
            return "{z: " + z +
                   ", updatedTileCount: " + getUpdatedTileCount() +
                   '}';
        }

        public void addTileId(final String tileId,
                              final int lineNumber,
                              final String modelClassName,
                              final String modelDataString) {

            if (tileIdToAlignTransformMap.containsKey(tileId)) {
                throw new IllegalArgumentException("Tile ID " + tileId + " is listed more than once in MET file " +
                                                   path + ".  The second reference was found at line " +
                                                   lineNumber + ".");
            }

            tileIdToAlignTransformMap.put(tileId, new LeafTransformSpec(modelClassName, modelDataString));

        }

        public void updateTiles() throws Exception {

            LOG.info("updateTiles: entry, z={}", z);

            updatedTiles = sourceRenderDataClient.getResolvedTiles(parameters.stack, z);

            if (tileSpecValidator != null) {
                updatedTiles.setTileSpecValidator(tileSpecValidator);
            }

            if (!updatedTiles.hasTileSpecs()) {
                throw new IllegalArgumentException(updatedTiles + " does not have any tiles");
            }

            LOG.info("updateTiles: filtering tile spec collection {}", updatedTiles);

            updatedTiles.filterSpecs(tileIdToAlignTransformMap.keySet());

            if (!updatedTiles.hasTileSpecs()) {
                throw new IllegalArgumentException("after filtering out non-aligned tiles, " +
                                                   updatedTiles + " does not have any remaining tiles");
            }

            LOG.info("updateTiles: after filter, collection is {}", updatedTiles);

            boolean removeExistingTransforms = false;
            boolean replaceLastTransform = true;
            if (ChangeMode.APPEND.equals(parameters.changeMode)) {
                replaceLastTransform = false;
            } else if (ChangeMode.REPLACE_ALL.equals(parameters.changeMode)) {
                removeExistingTransforms = true;
            }

            final int transformTileCount = tileIdToAlignTransformMap.size();
            final ProcessTimer timer = new ProcessTimer();
            int tileSpecCount = 0;
            TransformSpec alignTransform;
            TileSpec tileSpec;
            for (final String tileId : tileIdToAlignTransformMap.keySet()) {
                alignTransform = tileIdToAlignTransformMap.get(tileId);

                if (removeExistingTransforms) {

                    tileSpec = updatedTiles.getTileSpec(tileId);

                    if (tileSpec == null) {
                        throw new IllegalArgumentException("tile spec with id '" + tileId +
                                                           "' not found in " + updatedTiles +
                                                           ", possible issue with z value");
                    }

                    tileSpec.setTransforms(new ListTransformSpec());
                }

                updatedTiles.addTransformSpecToTile(tileId, alignTransform, replaceLastTransform);
                tileSpecCount++;
                if (timer.hasIntervalPassed()) {
                    LOG.info("updateTiles: updated transforms for {} out of {} tiles",
                             tileSpecCount, transformTileCount);
                }
            }

            updatedTiles.filterInvalidSpecs();

            final int removedTiles = tileSpecCount - updatedTiles.getTileCount();

            LOG.debug("updateTiles: updated transforms for {} tiles, removed {} bad tiles, elapsedSeconds={}",
                      tileSpecCount, removedTiles, timer.getElapsedSeconds());
        }

        public void saveTiles() throws Exception {

            LOG.info("saveTiles: entry, z={}", z);

            targetRenderDataClient.saveResolvedTiles(updatedTiles, parameters.targetStack, z);

            LOG.info("saveTiles: exit, saved tiles and transforms for {}", z);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportMETClient.class);

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
}
