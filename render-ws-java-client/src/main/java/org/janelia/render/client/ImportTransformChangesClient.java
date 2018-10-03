package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

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
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.APPEND;
import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.PRE_CONCATENATE_LAST;
import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.REPLACE_LAST;

/**
 * Java client for importing transform changes to existing tiles in the render database.
 *
 * @author Eric Trautman
 */
public class ImportTransformChangesClient {

    public enum ChangeMode { APPEND, REPLACE_LAST, CONCATENATE_TO_LAST, REPLACE_ALL }

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();

        @Parameter(
                names = "--stack",
                description = "Name of source stack containing base tile specifications",
                required = true)
        public String stack;

        @Parameter(names =
                "--targetOwner",
                description = "Name of owner for target stack that will contain imported transforms (default is to reuse source owner)"
        )
        public String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of project for target stack that will contain imported transforms (default is to reuse source project)"
        )
        public String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target (align, montage, etc.) stack that will contain imported transforms",
                required = true)
        public String targetStack;

        @Parameter(
                names = "--transformFile",
                description = "File containing list of transform changes (.json, .gz, or .zip).  For best performance, changes for all tiles with the same z should be grouped into the same file.",
                required = true)
        public String transformFile;

        @Parameter(
                names = "--changeMode",
                description = "Specifies how the transforms should be applied to existing data"
        )
        public ChangeMode changeMode = ChangeMode.REPLACE_LAST;

        String getTargetOwner() {
            if (targetOwner == null) {
                targetOwner = renderWeb.owner;
            }
            return targetOwner;
        }

        public String getTargetProject() {
            if (targetProject == null) {
                targetProject = renderWeb.project;
            }
            return targetProject;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

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

    private ImportTransformChangesClient(final Parameters parameters) {

        this.parameters = parameters;
        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();

        this.sourceRenderDataClient = parameters.renderWeb.getDataClient();

        this.targetRenderDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                           parameters.getTargetOwner(),
                                                           parameters.getTargetProject());

        this.zToDataMap = new HashMap<>();
    }

    private void updateStackData() throws Exception {

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

        boolean containsTile(final String tileId) {
            return (tileSpecs.getTileSpec(tileId) != null);
        }

        @Override
        public String toString() {
            return "{z: " + z + ", updatedTileCount: " + tileSpecs.getTileCount() + '}';
        }

        void addTileTransform(final TileTransform tileTransform)
                throws IllegalArgumentException {

            final String tileId = tileTransform.getTileId();

            if (tileIdToLoadedTransformMap.containsKey(tileId)) {
                throw new IllegalArgumentException("Tile ID " + tileId + " is listed more than once in " +
                                                   parameters.transformFile);
            }

            tileIdToLoadedTransformMap.put(tileId, tileTransform);
        }

        void updateTiles() {

            LOG.info("updateTiles: entry, z={}", z);

            if (tileSpecValidator != null) {
                tileSpecs.setTileSpecValidator(tileSpecValidator);
            }

            if (! tileSpecs.hasTileSpecs()) {
                throw new IllegalArgumentException(tileSpecs + " does not have any tiles");
            }

            LOG.info("updateTiles: filtering tile spec collection {}", tileSpecs);

            tileSpecs.removeDifferentTileSpecs(tileIdToLoadedTransformMap.keySet());

            if (! tileSpecs.hasTileSpecs()) {
                throw new IllegalArgumentException("after filtering out unreferenced source tiles, " +
                                                   tileSpecs + " does not have any remaining tiles");
            }

            LOG.info("updateTiles: after filter, collection is {}", tileSpecs);

            boolean removeExistingTransforms = false;
            ResolvedTileSpecCollection.TransformApplicationMethod applicationMethod = REPLACE_LAST;

            if (ChangeMode.APPEND.equals(parameters.changeMode)) {
                applicationMethod = APPEND;
            } else if (ChangeMode.CONCATENATE_TO_LAST.equals(parameters.changeMode)) {
                applicationMethod = PRE_CONCATENATE_LAST;
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

                tileSpecs.addTransformSpecToTile(tileId, tileTransform.getTransform(), applicationMethod);
                tileSpecCount++;

                if (timer.hasIntervalPassed()) {
                    LOG.info("updateTiles: updated transforms for {} out of {} tiles",
                             tileSpecCount, transformTileCount);
                }
            }

            tileSpecs.removeInvalidTileSpecs();

            final int removedTiles = tileSpecCount - tileSpecs.getTileCount();

            LOG.info("updateTiles: updated transforms for {} tiles, removed {} bad tiles, elapsedSeconds={}",
                     tileSpecCount, removedTiles, timer.getElapsedSeconds());
        }

        void saveTiles() throws Exception {

            LOG.info("saveTiles: entry, z={}", z);

            targetRenderDataClient.saveResolvedTiles(tileSpecs, parameters.targetStack, z);

            LOG.info("saveTiles: exit, saved tiles and transforms for {}", z);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportTransformChangesClient.class);
}
