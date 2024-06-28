package org.janelia.render.client.spark.n5;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.alignment.spec.transfer.ClusterRootPaths;
import org.janelia.alignment.spec.transfer.RenderDataSet;
import org.janelia.alignment.spec.transfer.ScopeDataSet;
import org.janelia.alignment.spec.transfer.VolumeTransferInfo;
import org.janelia.alignment.util.H5Util;
import org.janelia.alignment.util.NeuroglancerAttributes;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader;
import org.janelia.saalfeldlab.n5.spark.N5RemoveSpark;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.transform.SEMDistortionTransformA.DEFAULT_FIBSEM_CORRECTION_TRANSFORM;
import static org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid.N5ScalePyramidSpark.downsampleScalePyramid;

/**
 * Client for building and exporting preview volumes from h5 tile data sets.
 */
public class H5TileToN5PreviewClient {

    @SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal"})
    public static class Parameters
            extends CommandLineParameters {

        @Parameter(
                names = "--baseDataUrl",
                description = "Base web service URL for data (e.g. http://host[:port]/render-ws/v1)",
                required = true)
        public String baseDataUrl;

        @Parameter(
                names = "--transferInfo",
                description = "Transfer info JSON file for which preview should be generated.",
                required = true)
        public String transferInfoPath;

    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) {

                final H5TileToN5PreviewClient.Parameters parameters = new H5TileToN5PreviewClient.Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final H5TileToN5PreviewClient client = new H5TileToN5PreviewClient(parameters.baseDataUrl);

                final SparkConf conf = new SparkConf().setAppName("PreviewClient");
                final JavaSparkContext sparkContext = new JavaSparkContext(conf);

                final String transferInfoPath = parameters.transferInfoPath;
                try {
                    final VolumeTransferInfo volumeTransferInfo = VolumeTransferInfo.fromJsonFile(transferInfoPath);
                    if (volumeTransferInfo.hasExportPreviewVolumeTask()) {
                        client.buildAndExportPreview(sparkContext, volumeTransferInfo);
                    } else {
                        LOG.info("no export preview volume task defined in {}", transferInfoPath);
                    }
                } catch (final Exception e) {
                    final String errorMessage = "failed to build preview for " + transferInfoPath;
                    LOG.error(errorMessage, e);
                }

                sparkContext.close();
            }
        };
        clientRunner.run();
    }

    private final String baseDataUrl;

    /**
     * @param  baseDataUrl  base web service URL for data (e.g. http://host[:port]/render-ws/v1).
     */
    @SuppressWarnings("JavadocLinkAsPlainText")
    public H5TileToN5PreviewClient(final String baseDataUrl) {
        this.baseDataUrl = baseDataUrl;
    }

    /**
     * Builds and exports a preview volume from the h5 tile data set specified in the transfer info.
     *
     * @param  sparkContext        the spark context to use for parallel processing.
     * @param  volumeTransferInfo  the transfer info for the volume to be exported.
     *
     * @throws IllegalArgumentException
     *   if any problems with the transfer info are detected.
     *
     * @throws IOException
     *   if any problems occur setting up or executing the export.
     */
    public void buildAndExportPreview(final JavaSparkContext sparkContext,
                                      final VolumeTransferInfo volumeTransferInfo)
            throws IllegalArgumentException, IOException {

        LOG.info("buildAndExportPreview: entry, volumeTransferInfo={}", volumeTransferInfo);

        final StackMetaData stackMetaDataBeforeImport = setupPreviewStack(volumeTransferInfo);
        final StackMetaData stackMetaDataAfterImport = importPreviewTileData(sparkContext,
                                                                             volumeTransferInfo,
                                                                             stackMetaDataBeforeImport);

        final int maxZAfterImport = getIntegralMaxZForStack(stackMetaDataAfterImport);
        if (maxZAfterImport == 0) {
            LOG.warn("buildAndExportPreview: skipping export for {}, no z layers exist and none were imported",
                     stackMetaDataAfterImport.getStackId());
        } else {
            final ClusterRootPaths clusterRootPaths = volumeTransferInfo.getClusterRootPaths();
            if (clusterRootPaths.isExportN5Defined()) {
                final Path exportN5Path = Paths.get(clusterRootPaths.getExportN5()).toAbsolutePath();
                exportPreview(sparkContext, exportN5Path, stackMetaDataAfterImport);
            } else {
                LOG.warn("buildAndExportPreview: skipping export for {}, no exportN5 defined",
                         stackMetaDataAfterImport.getStackId());
            }
        }

        LOG.info("buildAndExportPreview: exit");
    }

    private StackMetaData setupPreviewStack(final VolumeTransferInfo volumeTransferInfo)
            throws IOException {

        final RenderDataSet renderDataSet = volumeTransferInfo.getRenderDataSet();
        final RenderDataClient driverRenderDataClient = new RenderDataClient(baseDataUrl,
                                                                             renderDataSet.getOwner(),
                                                                             renderDataSet.getProject());
        final String stackName = "imaging_preview";

        StackMetaData stackMetaData;
        try {
            stackMetaData = driverRenderDataClient.getStackMetaData(stackName);
        } catch (final Throwable t) {

            LOG.info("setupPreviewStack: {} stack does not exist, creating it ...", stackName);
            final StackVersion stackVersion = buildStackVersion(volumeTransferInfo.getScopeDataSet());
            driverRenderDataClient.saveStackVersion(stackName, stackVersion);
            stackMetaData = driverRenderDataClient.getStackMetaData(stackName);

        }

        if (! stackMetaData.isLoading()) {
            driverRenderDataClient.setStackState(stackName, StackMetaData.StackState.LOADING);
        }

        return stackMetaData;
    }

    private static StackVersion buildStackVersion(final ScopeDataSet scopeDataSet) {
        final Double xAndYNmPerPixel = scopeDataSet.getDatXAndYNmPerPixel().doubleValue();
        final Double zNmPerPixel = scopeDataSet.getDatZNmPerPixel().doubleValue();
        return new StackVersion(new Date(), null, null, null,
                                xAndYNmPerPixel, xAndYNmPerPixel, zNmPerPixel,
                                null, null);
    }

    private StackMetaData importPreviewTileData(final JavaSparkContext sparkContext,
                                                final VolumeTransferInfo volumeTransferInfo,
                                                final StackMetaData previewStackMetaData)
            throws IllegalArgumentException, IOException {

        LOG.info("importPreviewTileData: entry, importing data for {}", previewStackMetaData.getStackId());

        final ClusterRootPaths clusterRootPaths = volumeTransferInfo.getClusterRootPaths();
        final ScopeDataSet scopeDataSet = volumeTransferInfo.getScopeDataSet();

        final List<Path> sortedAlignH5Paths = buildSortedAlignH5Paths(scopeDataSet.getFirstDatName(),
                                                                      scopeDataSet.getLastDatName(),
                                                                      clusterRootPaths);

        final StackStats previewStackStats = previewStackMetaData.getStats();
        final Long existingSectionCount = previewStackStats == null ? null : previewStackStats.getSectionCount();
        final int startIndex = existingSectionCount == null ? 0 : existingSectionCount.intValue();
        final int numberOfZLayersToImport = sortedAlignH5Paths.size() - startIndex;

        // distribute work load as evenly as possible across executors
        final int zValuesPerBatch = (int) Math.ceil(numberOfZLayersToImport / (double) sparkContext.defaultParallelism());

        LOG.info("importPreviewTileData: found {} h5 files in {}, setting up import of {} new z layers",
                 sortedAlignH5Paths.size(), clusterRootPaths.getAlignH5(), numberOfZLayersToImport);

        final List<BatchedH5Paths> batchedH5Paths = new ArrayList<>();
        for (int fromIndex = startIndex; fromIndex < sortedAlignH5Paths.size(); fromIndex += zValuesPerBatch) {
            final int toIndex = Math.min(fromIndex + zValuesPerBatch, sortedAlignH5Paths.size());
            batchedH5Paths.add(new BatchedH5Paths(sortedAlignH5Paths.subList(fromIndex, toIndex),
                                                  (fromIndex + 1)));
        }

        LOG.info("importPreviewTileData: created {} import batches, each with a max of {} h5 files",
                 batchedH5Paths.size(), zValuesPerBatch);

        final int rowsPerZLayer = scopeDataSet.getRowsPerZLayer();
        final int columnsPerZLayer = scopeDataSet.getColumnsPerZLayer();

        final RenderDataSet renderDataSet = volumeTransferInfo.getRenderDataSet();
        final Integer maskWidth = renderDataSet.getMaskWidth();
        final Integer maskHeight = renderDataSet.getMaskHeight();

        final String fibsemCorrectTransformId = "FIBSEM_correct";
        final String referenceTransformId =
                volumeTransferInfo.hasApplyFibsemCorrectionTransformTask() ? fibsemCorrectTransformId : null;

        final StackId previewStackId = previewStackMetaData.getStackId();
        final String serializableBaseDataUrl = this.baseDataUrl;

        final JavaRDD<BatchedH5Paths> rddBatchedH5Paths = sparkContext.parallelize(batchedH5Paths);

        rddBatchedH5Paths.foreach(batchedH5Path -> {

            final ResolvedTileSpecCollection resolvedTileSpecCollection = new ResolvedTileSpecCollection();

            if (referenceTransformId != null) {
                final LeafTransformSpec referenceTransformSpec =
                        new LeafTransformSpec(fibsemCorrectTransformId,
                                              null,
                                              DEFAULT_FIBSEM_CORRECTION_TRANSFORM.getClass().getName(),
                                              DEFAULT_FIBSEM_CORRECTION_TRANSFORM.toDataString());
                resolvedTileSpecCollection.addTransformSpecToCollection(referenceTransformSpec);
                LOG.info("importPreviewTileData: to support tile spec bounding box derivation, added transform spec {}",
                         referenceTransformSpec.toJson());
            }

            for (int i = 0; i < batchedH5Path.h5PathStrings.size(); i++) {

                final double z = batchedH5Path.firstZ + i;
                final String h5PathString = batchedH5Path.h5PathStrings.get(i);

                try (final N5HDF5Reader reader = new N5HDF5Reader(h5PathString)) {
                    for (int row = 0; row < rowsPerZLayer; row++) {
                        for (int column = 0; column < columnsPerZLayer; column++) {
                            try {
                                final TileSpec tileSpec = H5Util.buildTileSpecWithoutBoundingBox(reader,
                                                                                                 h5PathString,
                                                                                                 row,
                                                                                                 column,
                                                                                                 z,
                                                                                                 maskWidth,
                                                                                                 maskHeight,
                                                                                                 referenceTransformId);

                                // adding tile spec to collection will resolve reference transform ...
                                resolvedTileSpecCollection.addTileSpecToCollection(tileSpec);

                                // ... making it safe to derive the bounding box
                                tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true, true);

                            } catch (final Exception e) {
                                LOG.warn("importPreviewTileData: failed to build tile spec for row {} and column {} of {} (z {})",
                                         row, column, h5PathString, z, e);
                            }
                        }
                    }
                } catch (final Exception e) {
                    throw new IOException("failed to import h5 file " + h5PathString, e);
                }

            }

            if (resolvedTileSpecCollection.getTileSpecs().isEmpty()) {
                throw new IOException("no tile specs were created for batch with first z " + batchedH5Path.firstZ +
                                      ", check worker logs for details");
            }

            final RenderDataClient workerRenderDataClient = new RenderDataClient(serializableBaseDataUrl,
                                                                                 previewStackId.getOwner(),
                                                                                 previewStackId.getProject());

            workerRenderDataClient.saveResolvedTiles(resolvedTileSpecCollection, previewStackId.getStack(), null);
        });

        final RenderDataClient driverRenderDataClient = new RenderDataClient(baseDataUrl,
                                                                             previewStackId.getOwner(),
                                                                             previewStackId.getProject());

        driverRenderDataClient.setStackState(previewStackId.getStack(), StackMetaData.StackState.COMPLETE);

        final StackMetaData stackMetaDataAfterImport = driverRenderDataClient.getStackMetaData(previewStackId.getStack());

        LOG.info("importPreviewTileData: exit, returning metadata with bounds {}",
                 stackMetaDataAfterImport.getStackBounds());
        
        return stackMetaDataAfterImport;
    }

    private static List<Path> buildSortedAlignH5Paths(final String firstDatName,
                                                      final String lastDatName,
                                                      final ClusterRootPaths clusterRootPaths)
            throws IllegalArgumentException, IOException {

        List<Path> sortedAlignH5Paths = clusterRootPaths.getSortedAlignH5Paths(firstDatName, lastDatName);

        if (sortedAlignH5Paths.isEmpty()) {
            throw new IllegalArgumentException("no h5 files found in " + clusterRootPaths.getAlignH5());
        }

        final List<Path> sortedParentDirs =
                sortedAlignH5Paths.stream().map(Path::getParent).distinct().sorted().collect(Collectors.toList());

        final FileTime twoHoursAgo = FileTime.from(Instant.now().minus(java.time.Duration.ofHours(2)));

        FileTime parentLastModifyTime = null;
        String firstRecentlyModifiedParentDirString = null;
        int indexOfFirstExcludedPath = sortedAlignH5Paths.size();
        for (final Path parentDir : sortedParentDirs) {

            parentLastModifyTime = Files.getLastModifiedTime(parentDir);
            if (parentLastModifyTime.compareTo(twoHoursAgo) < 0) {

                firstRecentlyModifiedParentDirString = parentDir.toString();

                for (int i = sortedAlignH5Paths.size() - 1; i >= 0; i--) {
                    final Path alignH5Path = sortedAlignH5Paths.get(i);
                    final String parentDirString = alignH5Path.getParent().toString();
                    if (parentDirString.compareTo(firstRecentlyModifiedParentDirString) >= 0) {
                        indexOfFirstExcludedPath = i;
                    } else {
                        break;
                    }
                }
            }
        }

        final int numberOfExcludedFiles = sortedAlignH5Paths.size() - indexOfFirstExcludedPath;
        if (numberOfExcludedFiles > 0) {

            LOG.info("buildSortedAlignH5Paths: excluding {} h5 files from {} (or later) since it was last modified at {}",
                     numberOfExcludedFiles, firstRecentlyModifiedParentDirString, parentLastModifyTime);

            sortedAlignH5Paths = sortedAlignH5Paths.subList(0, indexOfFirstExcludedPath);

            if (sortedAlignH5Paths.isEmpty()) {
                throw new IllegalArgumentException("no h5 files remain in " + clusterRootPaths.getAlignH5());
            }
        }

        return sortedAlignH5Paths;
    }

    private static class BatchedH5Paths implements Serializable {

        private final List<String> h5PathStrings;
        private final int firstZ;

        public BatchedH5Paths(final List<Path> h5Paths,
                              final int firstZ) {
            this.h5PathStrings = new ArrayList<>();
            for (final Path h5Path : h5Paths) {
                h5PathStrings.add(h5Path.toString()); // convert Path to String since Path is not serializable
            }
            this.firstZ = firstZ;
        }

    }

    private int getIntegralMaxZForStack(final StackMetaData stackMetaData) {
        Double maxZ = null;
        final StackStats stackStats = stackMetaData.getStats();
        if (stackStats != null) {
            final Bounds stackBounds = stackStats.getStackBounds();
            if (stackBounds != null) {
                maxZ = stackBounds.getMaxZ();
            }
        }
        return maxZ == null ? 0 : maxZ.intValue();
    }

    private static class ExportInfo implements Serializable {

        private final StackMetaData stackMetaData;
        private final long[] min;
        private final long[] dimensions;
        private final String n5PathString;
        private final String fullScaleDatasetName;
        private final File fullScaleDataSetDir;
        private final N5Client.Parameters parameters;

        public ExportInfo(final String baseDataUrl,
                          final StackMetaData stackMetaData,
                          final Path exportN5Path) {

            this.stackMetaData = stackMetaData;

            final StackId stackId = stackMetaData.getStackId();
            final Bounds bounds = stackMetaData.getStats().getStackBounds();

            this.min = new long[] {
                    bounds.getMinX().longValue(),
                    bounds.getMinY().longValue(),
                    bounds.getMinZ().longValue()
            };
            this.dimensions = new long[] {
                    (long) bounds.getDeltaX() + 1,
                    (long) bounds.getDeltaY() + 1,
                    (long) bounds.getDeltaZ() + 1
            };

            this.n5PathString = exportN5Path.toString();                                            // /nrs/cellmap/data/jrc_mus-liver-zon-3/jrc_mus-liver-zon-3.n5
            final String n5Dataset = "/render/" + stackId.getProject() + "/" + stackId.getStack();  // /render/jrc_mus_liver_zon_3/imaging_preview
            this.fullScaleDatasetName = n5Dataset + "/s0";                                          // /render/jrc_mus_liver_zon_3/imaging_preview/s0

            // /nrs/cellmap/data/jrc_mus-liver-zon-3/jrc_mus-liver-zon-3.n5/render/jrc_mus_liver_zon_3/imaging_preview/s0
            this.fullScaleDataSetDir = new File(n5PathString + fullScaleDatasetName);

            this.parameters = new N5Client.Parameters();
            this.parameters.renderWeb = new RenderWebServiceParameters(baseDataUrl,
                                                                       stackId.getOwner(),
                                                                       stackId.getProject());
            this.parameters.stack = stackId.getStack();
            this.parameters.n5Path = n5PathString;
            this.parameters.n5Dataset = n5Dataset;
            this.parameters.tileWidth = 4096;
            this.parameters.tileHeight = 4096;
            this.parameters.downsampleFactorsString = "2,2,2";

            // bumped preview block size up to 256x256 from 128x128 to reduce number of blocks
            this.parameters.blockSizeString = "256,256,64";
        }

        public String getViewParametersString() {
            return "-i " + n5PathString + " -d " + parameters.n5Dataset + " -o " + min[0] + "," + min[1] + "," + min[2];
        }

        public Bounds getBounds() {
            return stackMetaData.getStats().getStackBounds();
        }
    }

    private void exportPreview(final JavaSparkContext sparkContext,
                               final Path exportN5Path,
                               final StackMetaData stackMetaData)
            throws IllegalArgumentException, IOException {

        final ExportInfo exportInfo = new ExportInfo(baseDataUrl, stackMetaData, exportN5Path);
        final N5Client n5Client = new N5Client(exportInfo.parameters);
        final DataType dataType = n5Client.getDataType();
        final int[] blockSize = exportInfo.parameters.getBlockSize();

        LOG.info("exportPreview: view stack command is n5_view.sh {}", exportInfo.getViewParametersString());

        if (exportInfo.fullScaleDataSetDir.exists()) {

            final DatasetAttributes datasetAttributes;
            try (final N5Reader n5Reader = new N5FSReader(exportInfo.n5PathString)) {
                datasetAttributes = n5Reader.getDatasetAttributes(exportInfo.fullScaleDatasetName);
            }
            if (datasetAttributes == null) {
                throw new IllegalArgumentException("no attributes found for " + exportInfo.fullScaleDataSetDir);
            }

            final long minZToRender = findMinZToRender(datasetAttributes,
                                                       exportInfo.dimensions,
                                                       exportInfo.parameters.getBlockSize(),
                                                       dataType);

            // TODO: instead of removing all downsampled results, only remove subset that will be replaced
            removeDownsampledDatasets(sparkContext,
                                      exportInfo,
                                      minZToRender);

            appendToExistingPreviewExport(sparkContext,
                                          datasetAttributes,
                                          exportInfo,
                                          n5Client,
                                          blockSize,
                                          minZToRender);

        } else {

            // a prior export was not found, so create a new one ...
            N5Client.setupFullScaleExportN5(exportInfo.parameters,
                                            exportInfo.fullScaleDatasetName,
                                            stackMetaData,
                                            exportInfo.dimensions,
                                            blockSize,
                                            dataType);

            LOG.info("exportPreview: rendering {}{} for the first time with dimensions {}",
                     exportInfo.n5PathString, exportInfo.fullScaleDatasetName, exportInfo.dimensions);

            n5Client.renderStack(sparkContext,
                                 blockSize,
                                 exportInfo.fullScaleDatasetName,
                                 null,
                                 exportInfo.getBounds(),
                                 exportInfo.min,
                                 exportInfo.dimensions,
                                 false,
                                 N5Client.buildImageProcessorCacheSpec(),
                                 null); // always ignore minZToRender for initial render
        }

        final int[] downsampleFactors = exportInfo.parameters.getDownsampleFactors();
        LOG.info("exportPreview: downsample stack with factors {}", Arrays.toString(downsampleFactors));

        // Now that the full resolution image is saved into n5, generate the scale pyramid
        final N5WriterSupplier n5Supplier = new N5Client.N5PathSupplier(exportInfo.n5PathString);

        // TODO: if/when downsampled results are only partially removed, only downsample appended data

        final List<String> downsampledDatasetPaths =
                downsampleScalePyramid(sparkContext,
                                       n5Supplier,
                                       exportInfo.fullScaleDatasetName,
                                       exportInfo.parameters.n5Dataset,
                                       downsampleFactors);
        final int numberOfDownSampledDatasets = downsampledDatasetPaths.size();

        // save additional parameters so that n5 can be viewed in neuroglancer
        final NeuroglancerAttributes ngAttributes =
                new NeuroglancerAttributes(stackMetaData.getCurrentResolutionValues(),
                                           exportInfo.parameters.stackResolutionUnit,
                                           numberOfDownSampledDatasets,
                                           downsampleFactors,
                                           Arrays.asList(exportInfo.min[0], exportInfo.min[1], exportInfo.min[2]),
                                           NeuroglancerAttributes.NumpyContiguousOrdering.FORTRAN);

        ngAttributes.write(Paths.get(exportInfo.n5PathString),
                           Paths.get(exportInfo.fullScaleDatasetName));
    }

    private static long findMinZToRender(final DatasetAttributes datasetAttributes,
                                         final long[] dimensions,
                                         final int[] blockSize,
                                         final DataType dataType) throws IllegalArgumentException {

        if (! Arrays.equals(blockSize, datasetAttributes.getBlockSize())) {
            throw new IllegalArgumentException("append blockSize " + Arrays.toString(blockSize) + " does not match existing dataset block size " + Arrays.toString(datasetAttributes.getBlockSize()));
        }
        if (dataType != datasetAttributes.getDataType()) {
            throw new IllegalArgumentException("append dataType " + dataType + " does not match existing dataset data type " + datasetAttributes.getDataType());
        }
        final long[] existingDimensions = datasetAttributes.getDimensions();
        if (dimensions.length != existingDimensions.length) {
            throw new IllegalArgumentException("append dimensions " + Arrays.toString(dimensions) + " differ in length from existing dataset dimensions " + Arrays.toString(existingDimensions));
        }
        if (dimensions.length != 3) {
            throw new IllegalArgumentException("append export not supported for " + dimensions.length + "D volumes");
        }
        for (int i = 0; i < dimensions.length; i++) {
            if (dimensions[i] < existingDimensions[i]) {
                throw new IllegalArgumentException("append dimension " + i + " has shrunk, append dimensions are " + Arrays.toString(dimensions) + " but existing dataset dimensions are " + Arrays.toString(existingDimensions));
            }
        }
        final long minZToRender = existingDimensions[2] + 1;

        LOG.info("findMinZToRender: returning {}, maxZ is {}", minZToRender, dimensions[2]);

        return minZToRender;
    }

    private static void removeDownsampledDatasets(final JavaSparkContext sparkContext,
                                                  final ExportInfo exportInfo,
                                                  final long minZToRender)
            throws IllegalArgumentException, IOException {

        if (exportInfo.dimensions[2] <= minZToRender) {
            throw new IllegalArgumentException("nothing new to export since last z remains " + exportInfo.dimensions[2]);
        }

        final File parentDir = exportInfo.fullScaleDataSetDir.getParentFile();
        final File[] downsampledDirs = parentDir.listFiles(DOWNSAMPLED_DIR_FILTER);
        if (downsampledDirs != null) {
            final int datasetStart = exportInfo.parameters.n5Path.length() + 1;
            for (final File downsampledDir : downsampledDirs) {
                if (downsampledDir.isDirectory()) {
                    final String dsDatasetName = downsampledDir.getAbsolutePath().substring(datasetStart);
                    LOG.info("exportPreview: removing previously downsampled dataset {}", dsDatasetName);
                    N5RemoveSpark.remove(sparkContext,
                                         new N5Client.N5PathSupplier(exportInfo.parameters.n5Path),
                                         dsDatasetName);
                }
            }
        }
    }

    private static void appendToExistingPreviewExport(final JavaSparkContext sparkContext,
                                                      final DatasetAttributes datasetAttributes,
                                                      final ExportInfo exportInfo,
                                                      final N5Client n5Client,
                                                      final int[] blockSize,
                                                      final long minZToRender)
            throws IllegalArgumentException {

        try (final N5Writer n5Writer = new N5FSWriter(exportInfo.n5PathString)) {
            n5Writer.setDatasetAttributes(exportInfo.fullScaleDatasetName,
                                          new DatasetAttributes(exportInfo.dimensions,
                                                                blockSize,
                                                                datasetAttributes.getDataType(),
                                                                datasetAttributes.getCompression()));
        }

        N5Client.updateFullScaleExportAttributes(exportInfo.parameters,
                                                 exportInfo.fullScaleDatasetName,
                                                 exportInfo.stackMetaData);

        LOG.info("appendToExistingPreviewExport: rendering {}{} with minZToRender {} and dimensions {}",
                 exportInfo.n5PathString, exportInfo.fullScaleDatasetName, minZToRender, exportInfo.dimensions);

        n5Client.renderStack(sparkContext,
                             blockSize,
                             exportInfo.fullScaleDatasetName,
                             null,
                             exportInfo.getBounds(),
                             exportInfo.min,
                             exportInfo.dimensions,
                             false,
                             N5Client.buildImageProcessorCacheSpec(),
                             minZToRender);
    }

    private static final Logger LOG = LoggerFactory.getLogger(H5TileToN5PreviewClient.class);

    private static final Pattern DOWNSAMPLED_DIR_PATTERN = Pattern.compile("s[1-9]\\d*");
    private static final FileFilter DOWNSAMPLED_DIR_FILTER =
            pathname -> DOWNSAMPLED_DIR_PATTERN.matcher(pathname.getName()).matches();
}
