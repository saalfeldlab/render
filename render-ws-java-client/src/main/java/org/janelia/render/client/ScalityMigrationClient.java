package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.jfs.api.JFSFactory;
import org.janelia.jfs.fileshare.FileService;
import org.janelia.jfs.mongo.JOSObject;
import org.janelia.jfs.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for migrating source tile images to Scality and updating tile specs with Scality URLs.
 *
 * @author Eric Trautman
 */
public class ScalityMigrationClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Name of stack from which tile specs should be read", required = true)
        private String stack;

        @Parameter(names = "--targetProject", description = "Name of project to which updated tile specs should be written (default is source project)", required = false)
        private String targetProject;

        @Parameter(names = "--targetStack", description = "Name of stack to which updated tile specs should be written (default is source stack)", required = true)
        private String targetStack;

        @Parameter(names = "--jfsFileServiceId", description = "JFS file service ID (default is FAFB00_raw)", required = false)
        private String jfsFileServiceId = "FAFB00_raw";

        // TODO: remove if/when base Scality URL can be pulled from file service

        @Parameter(names = "--baseScalityUrl", description = "Base Scality URL (default is http://sc101-jrc:81/proxy/bparc2/)", required = false)
        private String baseScalityUrl = "http://sc101-jrc:81/proxy/bparc2/";

        @Parameter(names = "--insertMasks", description = "Insert mask files (default is false, assuming masks will remain on file system)", required = false, arity = 0)
        private boolean insertMasks = false;

        @Parameter(names = "--zValues", description = "Z values for filtering", required = false, variableArity = true)
        private List<String> zValues;

        public String getTargetProject() {
            return targetProject == null ? project : targetProject;
        }

        public String getTargetStack() {
            return targetStack == null ? stack : targetStack;
        }

     }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, ScalityMigrationClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ScalityMigrationClient client = new ScalityMigrationClient(parameters);

                for (final String z : parameters.zValues) {
                    client.migrateStackDataForZ(new Double(z));
                }
            }
        };
        clientRunner.run();

        // TODO: remove explicit exit call when JFS client is fixed
        System.exit(0);

    }

    // TODO: remove this debugging stuff ...
//    private static void logThreads(final String context) {
//        final Map<Thread, StackTraceElement[]> threadToStackTraceMap = Thread.getAllStackTraces();
//        for (final Thread thread : threadToStackTraceMap.keySet()) {
//            LOG.info("{} thread: {}", context, thread);
////            LOG.info("{} thread: {}, stackTrace: {}", context, thread, Arrays.asList(threadToStackTraceMap.get(thread)));
//        }
//    }

    private final Parameters parameters;

    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;

    private final FileService fileService;
    private final Map<String, String> fileServiceMetaData;
    private final Principal principal;
    private final String baseScalityUrlWithSlash;

    public ScalityMigrationClient(final Parameters parameters)
            throws Exception {

        this.parameters = parameters;

        this.sourceRenderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                           parameters.owner,
                                                           parameters.project);

        if (parameters.project.equals(parameters.getTargetProject())) {
            targetRenderDataClient = sourceRenderDataClient;
        } else {
            targetRenderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                          parameters.owner,
                                                          parameters.getTargetProject());
        }

        JFSFactory.initFactory();
        fileService = JFSFactory.initFileService(parameters.jfsFileServiceId, true);

        // file service meta data should already be configured in JFS, so no need to populate anything here
        fileServiceMetaData = new HashMap<>();

        // principal information not needed for FlyTEM stores since they do not have an authorizer
        principal = null;

        // TODO: assign base Scality URL from file service if/when that API becomes available
        if (parameters.baseScalityUrl.endsWith("/")) {
            baseScalityUrlWithSlash = parameters.baseScalityUrl;
        } else {
            baseScalityUrlWithSlash = parameters.baseScalityUrl + "/";
        }
    }

    private void migrateStackDataForZ(final Double z) throws Exception {

        LOG.info("migrateStackDataForZ: entry, z={}", z);

        final ResolvedTileSpecCollection resolvedTiles = sourceRenderDataClient.getResolvedTiles(parameters.stack, z);

        if (! resolvedTiles.hasTileSpecs()) {
            throw new IllegalArgumentException("the " + parameters.project + " project " + parameters.stack +
                                               " stack does not have any tiles");
        }

        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
            migrateTileSpec(tileSpec);
        }

        targetRenderDataClient.saveResolvedTiles(resolvedTiles, parameters.getTargetStack(), z);

        LOG.info("migrateStackDataForZ: exit");
    }

    private void migrateTileSpec(final TileSpec tileSpec)
            throws Exception {

        final Integer zeroLevelKey = 0;
        final Map.Entry<Integer, ImageAndMask> entry = tileSpec.getFirstMipmapEntry();

        if (entry == null) {

            LOG.warn("migrateTileSpec: no mipmap entries exist for tile id {}", tileSpec.getTileId());

        } else if (! zeroLevelKey.equals(entry.getKey())) {

            LOG.warn("migrateTileSpec: no zero level mipmap entry exists for tile id {}, first mipmap level is {}",
                     tileSpec.getTileId(), entry.getKey());

        } else {

            final ImageAndMask sourceImageAndMask = entry.getValue();
            final String scalityImageUrl = migrateFile(sourceImageAndMask.getImageFilePath());
            String maskUrl = sourceImageAndMask.getMaskUrl();
            if (parameters.insertMasks && sourceImageAndMask.hasMask()) {
                maskUrl = migrateFile(sourceImageAndMask.getMaskFilePath());
            }
            final ImageAndMask updatedImageAndMask = new ImageAndMask(scalityImageUrl, maskUrl);
            tileSpec.putMipmap(zeroLevelKey, updatedImageAndMask);

        }

    }

    private String migrateFile(final String filePath)
            throws Exception {

        JOSObject jfsMetaData = (JOSObject) fileService.getInfo(filePath);

        if (jfsMetaData == null) {

            LOG.info("migrateFile: saving {} in JFS", filePath);

            jfsMetaData = (JOSObject) fileService.putFile(new FileInputStream(new File(filePath)),
                                                          filePath,
                                                          fileServiceMetaData,
                                                          principal);

        } else {

            LOG.info("migrateFile: {} already saved in JFS", filePath);

        }

        final String scalityUrl = baseScalityUrlWithSlash + jfsMetaData.getScalityKey() + "?path=" + filePath;


        LOG.info("migrateFile: exit, returning {} for {}", scalityUrl, filePath);

        return scalityUrl;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ScalityMigrationClient.class);
}
