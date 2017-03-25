package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TileTransform;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for importing transform changes to existing tiles in the render database.
 *
 * @author Eric Trautman
 */
public class ImportTransformChangesClient {

    public enum ChangeMode { APPEND, REPLACE_LAST, REPLACE_ALL }

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParametersWithValidator {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters
        // NOTE: --validatorClass and --validatorData parameters defined in RenderDataClientParametersWithValidator

        @Parameter(
                names = "--stack",
                description = "Name of source stack containing base tile specifications",
                required = true)
        private String stack;

        @Parameter(names =
                "--targetOwner",
                description = "Name of owner for target stack that will contain imported transforms (default is to reuse source owner)",
                required = false)
        private String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of project for target stack that will contain imported transforms (default is to reuse source project)",
                required = false)
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target (align, montage, etc.) stack that will contain imported transforms",
                required = true)
        private String targetStack;

        @Parameter(
                names = "--transformFile",
                description = "File containing list of transform changes (.json, .gz, or .zip).  For best performance, changes for all tiles with the same z should be grouped into the same file.",
                required = true)
        private String transformFile;

        @Parameter(
                names = "--changeMode",
                description = "Specifies how the transforms should be applied to existing data",
                required = false)
        private ChangeMode changeMode = ChangeMode.REPLACE_LAST;

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
                parameters.parse(args, ImportTransformChangesClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ImportTransformChangesClient client = new ImportTransformChangesClient(parameters);

                client.updateStackData();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;

    private final Map<Double, SectionData> zToDataMap;

    public ImportTransformChangesClient(final Parameters parameters) {

        this.parameters = parameters;
        this.tileSpecValidator = parameters.getValidatorInstance();

        this.sourceRenderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                           parameters.owner,
                                                           parameters.project);

        this.targetRenderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                           parameters.getTargetOwner(),
                                                           parameters.getTargetProject());

        this.zToDataMap = new HashMap<>();
    }

    public void updateStackData() throws Exception {

        LOG.info("updateStackData: entry");

        final StackMetaData sourceStackMetaData = sourceRenderDataClient.getStackMetaData(parameters.stack);
        targetRenderDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);

        final List<TileTransform> transformDataList = loadTransformData(parameters.transformFile);
        for (final TileTransform tileTransform : transformDataList) {
            addTileTransformToSectionData(tileTransform);
        }

        for (final SectionData sectionData : zToDataMap.values()) {
            sectionData.updateTiles();
        }

        // only save updated data if all updates completed successfully
        for (final SectionData sectionData : zToDataMap.values()) {
            sectionData.saveTiles();
        }

        LOG.info("updateStackData: exit, saved tiles and transforms for {}", zToDataMap.values());
    }

    private List<TileTransform> loadTransformData(final String transformFile)
            throws IOException {

        final List<TileTransform> list;
        final Path path = Paths.get(transformFile).toAbsolutePath();

        LOG.info("loadTransformData: entry, path={}", path);

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            list = TileTransform.fromJsonArray(reader);
        }

        if (list.size() == 0) {
            throw new IOException("No transform information found in " + path + ".");
        }

        LOG.info("loadTransformData: exit, loaded {} tile transforms", list.size());

        return list;
    }

    private void addTileTransformToSectionData(final TileTransform tileTransform)
            throws IOException {

        final String tileId = tileTransform.getTileId();

        boolean tileFound = false;
        for (final SectionData sectionData : zToDataMap.values()) {
            if (sectionData.containsTile(tileId)) {
                tileFound = true;
                sectionData.addTileTransform(tileTransform);
                break;
            }
        }

        if (! tileFound) {

            LOG.info("addTileTransformToSectionData: retrieving z value and layer tiles for tileId {}", tileId);

            final TileSpec tileSpec = sourceRenderDataClient.getTile(parameters.stack, tileId);
            final Double z = tileSpec.getZ();
            final ResolvedTileSpecCollection resolvedTiles = sourceRenderDataClient.getResolvedTiles(parameters.stack,
                                                                                                     z);
            final SectionData sectionData = new SectionData(tileSpec.getZ(), resolvedTiles);
            sectionData.addTileTransform(tileTransform);
            zToDataMap.put(z, sectionData);
        }
    }

    private class SectionData {

        private final Double z;
        private final ResolvedTileSpecCollection tileSpecs;
        private final Map<String, TileTransform> tileIdToLoadedTransformMap;

        private SectionData(final Double z,
                            final ResolvedTileSpecCollection tileSpecs) {
            this.z = z;
            this.tileSpecs = tileSpecs;
            this.tileIdToLoadedTransformMap = new HashMap<>(tileSpecs.getTileCount() * 2);
        }

        public boolean containsTile(final String tileId) {
            return (tileSpecs.getTileSpec(tileId) != null);
        }

        @Override
        public String toString() {
            return "{z: " + z + ", updatedTileCount: " + tileSpecs.getTileCount() + '}';
        }

        public void addTileTransform(final TileTransform tileTransform)
                throws IllegalArgumentException {

            final String tileId = tileTransform.getTileId();

            if (tileIdToLoadedTransformMap.containsKey(tileId)) {
                throw new IllegalArgumentException("Tile ID " + tileId + " is listed more than once in " +
                                                   parameters.transformFile);
            }

            tileIdToLoadedTransformMap.put(tileId, tileTransform);
        }

        public void updateTiles() throws Exception {

            LOG.info("updateTiles: entry, z={}", z);

            if (tileSpecValidator != null) {
                tileSpecs.setTileSpecValidator(tileSpecValidator);
            }

            if (! tileSpecs.hasTileSpecs()) {
                throw new IllegalArgumentException(tileSpecs + " does not have any tiles");
            }

            LOG.info("updateTiles: filtering tile spec collection {}", tileSpecs);

            tileSpecs.filterSpecs(tileIdToLoadedTransformMap.keySet());

            if (! tileSpecs.hasTileSpecs()) {
                throw new IllegalArgumentException("after filtering out unreferenced source tiles, " +
                                                   tileSpecs + " does not have any remaining tiles");
            }

            LOG.info("updateTiles: after filter, collection is {}", tileSpecs);

            boolean removeExistingTransforms = false;
            boolean replaceLastTransform = true;
            if (ChangeMode.APPEND.equals(parameters.changeMode)) {
                replaceLastTransform = false;
            } else if (ChangeMode.REPLACE_ALL.equals(parameters.changeMode)) {
                removeExistingTransforms = true;
            }

            final int transformTileCount = tileIdToLoadedTransformMap.size();
            final ProcessTimer timer = new ProcessTimer();
            int tileSpecCount = 0;
            String tileId;
            TileSpec tileSpec;
            for (final TileTransform tileTransform : tileIdToLoadedTransformMap.values()) {

                tileId = tileTransform.getTileId();

                if (removeExistingTransforms) {
                    tileSpec = tileSpecs.getTileSpec(tileId);
                    if (tileSpec == null) {
                        throw new IllegalArgumentException("tile spec with id '" + tileId +
                                                           "' not found in " + tileSpecs);
                    }
                    // replace current transforms with an empty list
                    tileSpec.setTransforms(new ListTransformSpec());
                }

                tileSpecs.addTransformSpecToTile(tileId, tileTransform.getTransform(), replaceLastTransform);
                tileSpecCount++;

                if (timer.hasIntervalPassed()) {
                    LOG.info("updateTiles: updated transforms for {} out of {} tiles",
                             tileSpecCount, transformTileCount);
                }
            }

            tileSpecs.filterInvalidSpecs();

            final int removedTiles = tileSpecCount - tileSpecs.getTileCount();

            LOG.info("updateTiles: updated transforms for {} tiles, removed {} bad tiles, elapsedSeconds={}",
                     tileSpecCount, removedTiles, timer.getElapsedSeconds());
        }

        public void saveTiles() throws Exception {

            LOG.info("saveTiles: entry, z={}", z);

            targetRenderDataClient.saveResolvedTiles(tileSpecs, parameters.targetStack, z);

            LOG.info("saveTiles: exit, saved tiles and transforms for {}", z);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportTransformChangesClient.class);
}
