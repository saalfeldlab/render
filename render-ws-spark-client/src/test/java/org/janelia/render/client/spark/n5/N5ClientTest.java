package org.janelia.render.client.spark.n5;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCacheSpec;
import org.janelia.alignment.util.NeuroglancerAttributes;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.zspacing.ThicknessCorrectionData;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the {@link N5Client} class.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("SameParameterValue")
public class N5ClientTest {

    private StackMetaData stackMetaData;
    private File n5PathDirectory;

    @Before
    public void setup() {
        final StackVersion stackVersion = new StackVersion(new Date(),
                                                           null,
                                                           null,
                                                           null,
                                                           2.0,
                                                           4.0,
                                                           6.0,
                                                           null,
                                                           null);
        stackMetaData = new StackMetaData(new StackId("o", "p", "s"),
                                          stackVersion);

        final SimpleDateFormat sdf = new SimpleDateFormat("'test_n5_'yyyyMMdd_HHmmss");
        final String n5Path = Paths.get(sdf.format(new Date())).toAbsolutePath().toString();
        n5PathDirectory = new File(n5Path);
    }

    @After
    public void tearDown() {
        if (n5PathDirectory.exists()) {
            FileUtil.deleteRecursive(n5PathDirectory);
        }
    }

    @Test
    public void testParameterParsing() throws Exception {
        final N5Client.Parameters p = new N5Client.Parameters();
        CommandLineParameters.parseHelp(p);

        p.downsampleFactorsString = "2,2,2";
        final int[] downSampleFactors = p.getDownsampleFactors();
        Assert.assertNotNull("null downSampleFactors returned", downSampleFactors);
        Assert.assertEquals("wrong number of downSampleFactors returned",
                            3, downSampleFactors.length);
    }

    @Test
    public void testGetBoundsForRun() {
        final N5Client.Parameters p = new N5Client.Parameters();
        final Bounds stackBounds = new Bounds(222.0,333.0,1.0,444.0,555.0,6.0);
        Bounds boundsForRun = p.getBoundsForRun(stackBounds, null);
        Assert.assertEquals("null parameters should simply return stack bounds", stackBounds, boundsForRun);

        final ThicknessCorrectionData thicknessCorrectionData = new ThicknessCorrectionData(Arrays.asList(
                "1 1.00001", "2 2.45", "3 3", "4 4", "5 4.85", "6 6.00004"
        ));

        boundsForRun = p.getBoundsForRun(stackBounds, thicknessCorrectionData);
        final Bounds expectedThicknessBounds = new Bounds(stackBounds.getMinX(),
                                                          stackBounds.getMinY(),
                                                          1.0,
                                                          stackBounds.getMaxX(),
                                                          stackBounds.getMaxY(),
                                                          6.0);
        Assert.assertEquals("incorrect thickness data bounds for normal data",
                            expectedThicknessBounds, boundsForRun);
    }

    @Test
    public void testSetupFullScaleExportN5() throws Exception {

        final Path n5Path = n5PathDirectory.toPath().toAbsolutePath();
        final Path fullScaleDatasetPath = Paths.get("/z_corr/v4_acquire_align_ic/s0");
        final String datasetName = fullScaleDatasetPath.toString();

        final String[] runArgs = {
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "cellmap",
                "--project", "jrc_zf_cardiac_1",
                "--stack", "v4_acquire_align_ic",
                "--n5Path", n5Path.toString(),
                "--n5Dataset", datasetName,
                "--tileWidth", "4096",
                "--tileHeight", "4096",
                "--blockSize", "128,128,64",
                "--factors", "2,2,2",
                "--z_coords", "/test/data/solve_20221011_115958/Zcoords.txt"
        };
        final N5Client.Parameters p = new N5Client.Parameters();
        p.parse(runArgs);

        final long[] dimensions = { 100L, 200L, 300L };
        final int[] blockSize = p.getBlockSize();

        N5Client.setupFullScaleExportN5(p, datasetName, stackMetaData, dimensions, blockSize, DataType.UINT8);

        try (final N5Reader n5Reader = new N5FSReader(n5Path.toString())) {
            Assert.assertTrue("dataset " + datasetName + " is missing", n5Reader.datasetExists(datasetName));

            final String exportAttributesDatasetName = datasetName.substring(0, datasetName.length() - 3);
            final Object renderExport = n5Reader.getAttribute(exportAttributesDatasetName,
                                                              "renderExport",
                                                              Map.class);
            Assert.assertNotNull("renderExport missing from " + exportAttributesDatasetName, renderExport);

            final String formattedAttributes = JsonUtils.MAPPER.writeValueAsString(renderExport);
            System.out.println(formattedAttributes);
        }
    }

    @Test
    public void testNeuroglancerAttributes() throws Exception {

        final Path n5Path = n5PathDirectory.toPath().toAbsolutePath();
        final Path fullScaleDatasetPath = Paths.get("/render/test_stack/one_more_nested_dir/s0");
        final String datasetName = fullScaleDatasetPath.toString();

        final long[] dimensions = { 100L, 200L, 300L };
        final int[] blockSize = { 10, 20, 30 };
        try (final N5Writer n5Writer = new N5FSWriter(n5Path.toString())) {

            final DatasetAttributes datasetAttributes = new DatasetAttributes(dimensions,
                                                                              blockSize,
                                                                              DataType.UINT8,
                                                                              new GzipCompression());
            n5Writer.createDataset(datasetName, datasetAttributes);

            final N5Reader n5Reader = new N5FSReader(n5Path.toString());
            Assert.assertTrue("dataset " + datasetName + " is missing", n5Reader.datasetExists(datasetName));

            final Map<String, Object> originalDatasetAttributes = datasetAttributes.asMap();
            final Map<String, Object> writtenDatasetAttributes = n5Reader.getDatasetAttributes(datasetName).asMap();
            Assert.assertEquals("incorrect number of dataset attributes were written",
                                originalDatasetAttributes.size(), writtenDatasetAttributes.size());

            for (final String key : originalDatasetAttributes.keySet()) {
                Assert.assertTrue(key + " attribute not written",
                                  writtenDatasetAttributes.containsKey(key));
            }

            // need to create downsample scale level data set directories and attributes.json files
            final int numberOfDownsampledDatasets = 3;
            final Path multiScaleParentPath = fullScaleDatasetPath.getParent();
            for (int scaleLevel = 1; scaleLevel <= numberOfDownsampledDatasets; scaleLevel++) {
                final DatasetAttributes scaleLevelAttributes = new DatasetAttributes(dimensions,
                                                                                     blockSize,
                                                                                     DataType.UINT8,
                                                                                     new GzipCompression());
                final String scaleLevelDatasetName = multiScaleParentPath + "/s" + scaleLevel;
                n5Writer.createDataset(scaleLevelDatasetName, scaleLevelAttributes);
            }

            final List<Double> resolutionValues = stackMetaData.getCurrentResolutionValues();
            final NeuroglancerAttributes ngAttributes =
                    new NeuroglancerAttributes(resolutionValues,
                                               "nm",
                                               numberOfDownsampledDatasets,
                                               new int[]{2, 2, 2},
                                               Arrays.asList(5L, 25L, 125L),
                                               NeuroglancerAttributes.NumpyContiguousOrdering.C);

            ngAttributes.write(n5Path, fullScaleDatasetPath);

            final String testStackDatasetName = fullScaleDatasetPath.getParent().toString();

            @SuppressWarnings("unchecked") final List<String> axes = n5Reader.getAttribute(testStackDatasetName,
                                                                                           "axes",
                                                                                           List.class);

            Assert.assertNotNull("axes attributes not written to dataset " + testStackDatasetName, axes);
            Assert.assertArrayEquals("invalid axes attributes written",
                                     NeuroglancerAttributes.RENDER_AXES.toArray(), axes.toArray());

            final String renderDatasetName = fullScaleDatasetPath.getParent().getParent().toString();

            final Boolean flag = n5Reader.getAttribute(renderDatasetName,
                                                       NeuroglancerAttributes.SUPPORTED_KEY,
                                                       Boolean.class);

            Assert.assertEquals(NeuroglancerAttributes.SUPPORTED_KEY +
                                " attributes not written to dataset " + renderDatasetName,
                                Boolean.TRUE, flag);

            validateTransformElement("level 0",
                                     n5Reader,
                                     fullScaleDatasetPath.toString(),
                                     axes,
                                     resolutionValues);

            validateTransformElement("level 3",
                                     n5Reader,
                                     fullScaleDatasetPath.getParent() + "/s3",
                                     axes,
                                     Arrays.asList(resolutionValues.get(0) * 8,
                                                   resolutionValues.get(1) * 8,
                                                   resolutionValues.get(2) * 8));
        }
    }

    @SuppressWarnings("unchecked")
    private void validateTransformElement(final String context,
                                          final N5Reader n5Reader,
                                          final String levelDatasetName,
                                          final List<String> parentAxes,
                                          final List<Double> expectedScaleList) {

        final Map<String, Object> transformLevel0 = n5Reader.getAttribute(levelDatasetName,
                                                                          "transform",
                                                                          Map.class);
        Assert.assertNotNull(context + " transform element not found ", transformLevel0);

        Assert.assertTrue(context + " transform is missing axes element",
                          transformLevel0.containsKey("axes"));

        final List<String> levelAxes = (List<String>) transformLevel0.get("axes");
        Assert.assertEquals(context + " axes element differs from parent",
                            parentAxes, levelAxes);

        Assert.assertTrue(context + " transform is missing scale element",
                          transformLevel0.containsKey("scale"));

        final List<Double> scaleList = (List<Double>) transformLevel0.get("scale");
        Assert.assertEquals(context + " scale element differs from parent",
                            expectedScaleList, scaleList);
    }

    public static void main(final String[] args) {

        final N5Client.Parameters parameters = new N5Client.Parameters();

        parameters.renderWeb = new RenderWebServiceParameters(
                "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "hess_wafer_53_center7",
                "slab_000_to_009");
        parameters.stack = "s001_m239_align_no35";
        parameters.tileWidth = 2048;
        parameters.tileHeight = 2048;
        parameters.n5Path = System.getenv("HOME") + "/Desktop/test.n5";
        parameters.n5Dataset = "/output";

        final N5Client n5Client = new N5Client(parameters);

        final SparkConf sparkConf = new SparkConf()
                .setMaster("local[4]") // run spark locally with 4 threads
                .set("spark.driver.bindAddress", "127.0.0.1")
                .setAppName("test");
        final JavaSparkContext sparkContext = new JavaSparkContext(sparkConf);

        final int[] blockSize = new int[] {1024, 1024, 4};
        final String fullScaleDatasetName = parameters.n5Dataset + "/s0";
        final Bounds boundsForRun = new Bounds(40000.0, 40000.0, 20.0,
                                               44000.0, 44000.0, 22.0);
        final long[] min = {
                boundsForRun.getMinX().longValue(),
                boundsForRun.getMinY().longValue(),
                boundsForRun.getMinZ().longValue()
        };
        final long[] dimensions = {
                Double.valueOf(boundsForRun.getDeltaX() + 1).longValue(),
                Double.valueOf(boundsForRun.getDeltaY() + 1).longValue(),
                Double.valueOf(boundsForRun.getDeltaZ() + 1).longValue()
        };
        final ImageProcessorCacheSpec cacheSpec = N5Client.buildImageProcessorCacheSpec();

        try (final N5Writer n5 = new N5FSWriter(parameters.n5Path)) {
            n5.createDataset(fullScaleDatasetName,
                             dimensions,
                             blockSize,
                             DataType.UINT8,
                             new GzipCompression());
        }

        n5Client.renderStack(sparkContext,
                             blockSize,
                             fullScaleDatasetName,
                             null,
                             boundsForRun,
                             min,
                             dimensions,
                             false,
                             cacheSpec,
                             null);
    }

}
