package org.janelia.render.client.spark.n5;

import java.io.File;
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
        final Bounds stackBounds = new Bounds(1.0,2.0,3.0,4.0,5.0,6.0);
        stackMetaData.setStats(new StackStats(stackBounds,
                                              1L, 1L, 1L, 1L,
                                              1, 1, 1, 1,
                                              null));
        final Bounds boundsForRun = p.getBoundsForRun(stackMetaData);
        Assert.assertEquals("null parameters should simply return stack bounds", stackBounds, boundsForRun);
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

        final NeuroglancerAttributes ngAttributes =
                new NeuroglancerAttributes(stackMetaData.getCurrentResolutionValues(),
                                           "nm",
                                           3,
                                           new int[] {2, 2, 2},
                                           Arrays.asList(5L, 25L, 125L));
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
    }

}
