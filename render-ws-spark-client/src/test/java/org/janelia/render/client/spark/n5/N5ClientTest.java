package org.janelia.render.client.spark.n5;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
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
        stackMetaData.setStats(new StackStats(stackBounds,
                                              1L, 1L, 1L, 1L,
                                              1, 1, 1, 1,
                                              null));
        Bounds boundsForRun = p.getBoundsForRun(stackMetaData, null);
        Assert.assertEquals("null parameters should simply return stack bounds", stackBounds, boundsForRun);

        final ThicknessCorrectionData thicknessCorrectionData = new ThicknessCorrectionData(Arrays.asList(
                "1 1.23", "2 2.45", "3 3", "4 4", "5 4.85", "6 5.64"
        ));

        boundsForRun = p.getBoundsForRun(stackMetaData, thicknessCorrectionData);
        final Bounds expectedThicknessBounds = new Bounds(stackBounds.getMinX(),
                                                          stackBounds.getMinY(),
                                                          2.0,
                                                          stackBounds.getMaxX(),
                                                          stackBounds.getMaxY(),
                                                          5.0);
        Assert.assertEquals("incorrect thickness data bounds",
                            expectedThicknessBounds, boundsForRun);
    }

    @Test
    public void testNeuroglancerAttributes() throws Exception {

        final Path n5Path = n5PathDirectory.toPath().toAbsolutePath();
        final Path fullScaleDatasetPath = Paths.get("/render/test_stack/one_more_nested_dir/s0");
        final String datasetName = fullScaleDatasetPath.toString();

        final long[] dimensions = { 100L, 200L, 300L };
        final int[] blockSize = { 10, 20, 30 };
        final N5Writer n5Writer = new N5FSWriter(n5Path.toString());

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
                                           new int[] {2, 2, 2},
                                           Arrays.asList(5L, 25L, 125L),
                                           NeuroglancerAttributes.NumpyContiguousOrdering.C);

        ngAttributes.write(n5Path, fullScaleDatasetPath);

        final String testStackDatasetName = fullScaleDatasetPath.getParent().toString();

        @SuppressWarnings("unchecked")
        final List<String> axes = n5Reader.getAttribute(testStackDatasetName,
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

    @SuppressWarnings("unchecked")
    private void validateTransformElement(final String context,
                                          final N5Reader n5Reader,
                                          final String levelDatasetName,
                                          final List<String> parentAxes,
                                          final List<Double> expectedScaleList)
            throws IOException {

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
}
