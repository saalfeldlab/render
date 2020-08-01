package org.janelia.alignment;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.util.Map;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.TransformMesh;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.loader.ImageJDefaultLoader;
import org.janelia.alignment.mapper.SingleChannelWithAlphaMapper;
import org.janelia.alignment.spec.TileSpec;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A test of mesh operations to ensure that performance enhancements do not regress results.
 *
 * @author Eric Trautman
 */
@Ignore
public class TransformMeshTest {

    // increase this to 10 (or more) to see average times
    private static final int NUMBER_OF_RUNS_PER_TEST = 1;

    private TileSpec tileSpec;
    private CoordinateTransformList<CoordinateTransform> ctlMipmap;
    private ImageProcessor ipMipmap;
    private ImageProcessor maskSourceProcessor;
    private ImageProcessor maskTargetProcessor;
    private ImageProcessor tp;

    @Before
    public void setup() {

        tileSpec = TileSpec.fromJson(TILE_SPEC_JSON);

        ctlMipmap = new CoordinateTransformList<>();
        for (final CoordinateTransform t : tileSpec.getTransformList().getList(null)) {
            ctlMipmap.add(t);
        }

        final Map.Entry<Integer, ImageAndMask> mipmapEntry = tileSpec.getFirstMipmapEntry();
        final ImageAndMask imageAndMask = mipmapEntry.getValue();
        ipMipmap = ImageJDefaultLoader.INSTANCE.load(imageAndMask.getImageUrl());

        tp = ipMipmap.createProcessor(ipMipmap.getWidth(), ipMipmap.getHeight());

        maskSourceProcessor = ImageJDefaultLoader.INSTANCE.load(imageAndMask.getMaskUrl());
        maskTargetProcessor = new ByteProcessor(tp.getWidth(), tp.getHeight());

    }

    @Test
    public void testRenderMeshOperations() {

        for (int i = 0; i < NUMBER_OF_RUNS_PER_TEST; ++i) {

            final long start = System.currentTimeMillis();

            // create mesh
            final RenderTransformMesh mesh = new RenderTransformMesh(ctlMipmap, (int) (tileSpec.getWidth() / tileSpec.getMeshCellSize() + 0.5),
                    ipMipmap.getWidth(), ipMipmap.getHeight());
            mesh.updateAffines();

            final long meshCreationStop = System.currentTimeMillis();

            final ImageProcessorWithMasks source = new ImageProcessorWithMasks(ipMipmap, maskSourceProcessor, null);

            final ImageProcessorWithMasks target = new ImageProcessorWithMasks(tp, maskTargetProcessor, null);

            final RenderTransformMeshMappingWithMasks mapping = new RenderTransformMeshMappingWithMasks(mesh);
            mapping.map(new SingleChannelWithAlphaMapper(source, target, true), 1);

            final long mapInterpolatedStop = System.currentTimeMillis();

            // old perf measurements on Mac: mesh: 65-75, map: 425-510
            LOG.info("RenderTransformMeshMapping times: mesh:{}, map:{}", meshCreationStop - start, mapInterpolatedStop - meshCreationStop);

            final int expectedPixelCount = 5989000;
            Assert.assertEquals("target image has invalid number of pixels", expectedPixelCount, target.ip.getPixelCount());

            final int[] expectedPixelValues = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 135, 107, 118, 126, 171, 103, 189, 129, 178, 130, 0, 0, 100, 151, 122,
                    122, 105, 169, 155, 179, 126, 131, 0, 0, 149, 100, 107, 185, 130, 163, 138, 189, 187, 194, 0, 0, 179, 153, 168, 171, 181, 128, 119, 132,
                    195, 113, 0, 0, 129, 150, 118, 179, 93, 185, 135, 78, 106, 185, 0, 0, 136, 164, 136, 184, 167, 184, 150, 182, 143, 172, 0, 0, 120, 134,
                    140, 67, 92, 76, 112, 178, 96, 185, 0, 0, 101, 81, 171, 138, 117, 147, 145, 162, 114, 97, 0, 0, 131, 157, 173, 170, 81, 157, 148, 177, 173,
                    160, 0, 0, 142, 101, 93, 90, 131, 139, 127, 173, 185, 150, 0, 0, 166, 161, 105, 165, 109, 165, 155, 92, 152, 154, 0, 0, 169, 89, 87, 163,
                    162, 142, 109, 177, 155, 104, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

            int index = 0;
            for (int x = 0; x < target.getWidth(); x += 200) {
                for (int y = 0; y < target.getHeight(); y += 200) {
                    Assert.assertEquals("target pixel (" + x + ", " + y + ") has invalid value", expectedPixelValues[index],
                                        target.ip.getPixel(x, y));
                    index++;
                }
            }
        }
    }

    @Test
    public void testMeshOperations() {

        for (int i = 0; i < NUMBER_OF_RUNS_PER_TEST; ++i) {
            final long start = System.currentTimeMillis();

            // create mesh
            final CoordinateTransformMesh mesh = new CoordinateTransformMesh(ctlMipmap, (int) (tileSpec.getWidth() / tileSpec.getMeshCellSize() + 0.5),
                    ipMipmap.getWidth(), ipMipmap.getHeight());

            final long meshCreationStop = System.currentTimeMillis();

            final ImageProcessorWithMasks source = new ImageProcessorWithMasks(ipMipmap,
                    maskSourceProcessor, null);

            final ImageProcessorWithMasks target = new ImageProcessorWithMasks(tp,
                    maskTargetProcessor, null);

            final TransformMeshMappingWithMasks<TransformMesh> mapping = new TransformMeshMappingWithMasks<>(mesh);
            mapping.mapInterpolated(source, target, 1);

            final long mapInterpolatedStop = System.currentTimeMillis();

            // old perf measurements on Mac: mesh: 65-75, map: 425-510
            LOG.info("TransformMeshMapping times: mesh:{}, map:{}", meshCreationStop - start, mapInterpolatedStop - meshCreationStop);

            final ImageProcessor targetImageProcessor = target.ip;

            final int expectedPixelCount = 5989000;
            Assert.assertEquals("target image has invalid number of pixels", expectedPixelCount, targetImageProcessor.getPixelCount());

            final int[] expectedPixelValues = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 135, 107, 118, 126, 171, 103, 189, 129, 178, 130, 0, 0, 100, 151, 122,
                    122, 105, 169, 155, 179, 126, 131, 0, 0, 149, 100, 107, 185, 130, 163, 138, 189, 187, 194, 0, 0, 179, 153, 168, 171, 181, 128, 119, 132,
                    195, 113, 0, 0, 129, 150, 118, 179, 93, 185, 135, 78, 106, 185, 0, 0, 136, 164, 136, 184, 167, 184, 150, 182, 143, 172, 0, 0, 120, 134,
                    140, 67, 92, 76, 112, 178, 96, 185, 0, 0, 101, 81, 171, 138, 117, 147, 145, 162, 114, 97, 0, 0, 131, 157, 173, 170, 81, 157, 148, 177, 173,
                    160, 0, 0, 142, 101, 93, 90, 131, 139, 127, 173, 185, 150, 0, 0, 166, 161, 105, 165, 109, 165, 155, 92, 152, 154, 0, 0, 169, 89, 87, 163,
                    162, 142, 109, 177, 155, 104, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

            int index = 0;
            for (int x = 0; x < targetImageProcessor.getWidth(); x += 200) {
                for (int y = 0; y < targetImageProcessor.getHeight(); y += 200) {
                    Assert.assertEquals("target pixel (" + x + ", " + y + ") has invalid value", expectedPixelValues[index],
                            targetImageProcessor.getPixel(x, y));
                    index++;
                }
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(TransformMeshTest.class);

    private static final String TILE_SPEC_JSON =
            "{\n" +
            "  \"width\": 2650.0,\n" +
            "  \"height\": 2260.0,\n" +
            "  \"mipmapLevels\": {\n" +
            "    \"0\": {\n" +
            "      \"imageUrl\": \"src/test/resources/stitch-test/col0075_row0021_cam1.png\",\n" +
            "      \"maskUrl\": \"src/test/resources/stitch-test/test_mask.jpg\"\n" +
            "    }\n" +
            "  },\n" +
            "  \"transforms\": {\n" +
            "    \"type\": \"list\",\n" +
            "    \"specList\": [\n" +
            "      {\n" +
            "        \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "        \"dataString\": \"0.959851    -0.007319      0.00872     0.923958      47.5933      45.6929\"\n" +
            "      },\n" +
            "      {\n" +
            "        \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "        \"dataString\": \"1  0  0  1  0  0\"\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";
}
