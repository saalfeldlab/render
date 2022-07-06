package org.janelia.alignment.spec;

import java.util.Map;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link MipmapPathBuilder} class.
 *
 * @author Eric Trautman
 */
public class MipmapPathBuilderTest {

    @Test
    public void testJsonProcessing() {

        final MipmapPathBuilder mipmapPathBuilder =
                new MipmapPathBuilder("/mipmaps",
                                      1,
                                      "tif",
                                      null);
        final String json = mipmapPathBuilder.toJson();

        Assert.assertNotNull("json generation returned null string", json);

        final MipmapPathBuilder parsedBuilder = MipmapPathBuilder.fromJson(json);
        Assert.assertNotNull("null builder returned from json parse", parsedBuilder);
    }

    @Test
    public void testDeriveImageAndMask() {
        final int mipmapLevel = 3;
        MipmapPathBuilder mipmapPathBuilder =
                new MipmapPathBuilder("/mipmaps",
                                      7,
                                      "tif",
                                      null);

        Map.Entry<Integer, ImageAndMask> sourceEntry = buildMipmapEntry(
                "file:///data/Merlin-6257_21-05-20_125416_0-0-0_InLens.png",
                null,
                "file:///masks/test-mask.png",
                ImageLoader.LoaderType.IMAGEJ_DEFAULT);

        ImageAndMask derivedImageAndMask =
                mipmapPathBuilder.deriveImageAndMask(mipmapLevel, sourceEntry, false).getValue();

        String expectedImageUrl = "file:/mipmaps/" + mipmapLevel +
                                  "/data/Merlin-6257_21-05-20_125416_0-0-0_InLens.png.tif";
        Assert.assertEquals("invalid derived imageUrl for " + sourceEntry.getValue(),
                            expectedImageUrl, derivedImageAndMask.getImageUrl());

        String expectedMaskUrl = "file:/mipmaps/" + mipmapLevel + "/masks/test-mask.png.tif";
        Assert.assertEquals("invalid derived maskUrl for " + sourceEntry.getValue(),
                            expectedMaskUrl, derivedImageAndMask.getMaskUrl());


        mipmapPathBuilder = new MipmapPathBuilder("/mipmaps",
                                                  7,
                                                  "tif",
                                                  MipmapPathBuilder.JANELIA_FIBSEM_H5_MIPMAP_PATTERN_STRING);

        sourceEntry = buildMipmapEntry(
                "file:///Merlin-6257_21-05-20_125416.uint8.h5?dataSet=0-0-0.mipmap.0&z=0",
                ImageLoader.LoaderType.H5_SLICE,
                "file:///masks/test-another-mask.png",
                ImageLoader.LoaderType.IMAGEJ_DEFAULT);

        derivedImageAndMask = mipmapPathBuilder.deriveImageAndMask(mipmapLevel, sourceEntry, false).getValue();

        expectedImageUrl = "file:///Merlin-6257_21-05-20_125416.uint8.h5?dataSet=0-0-0.mipmap." + mipmapLevel + "&z=0";
        Assert.assertEquals("invalid derived imageUrl for " + sourceEntry.getValue(),
                            expectedImageUrl, derivedImageAndMask.getImageUrl());

        Assert.assertEquals("invalid derived imageLoaderType for " + sourceEntry.getValue(),
                            ImageLoader.LoaderType.H5_SLICE, derivedImageAndMask.getImageLoaderType());

        expectedMaskUrl = "file:/mipmaps/" + mipmapLevel + "/masks/test-another-mask.png.tif";
        Assert.assertEquals("invalid derived maskUrl for " + sourceEntry.getValue(),
                            expectedMaskUrl, derivedImageAndMask.getMaskUrl());

        final String baseMaskUrl = "mask://outside-box?minX=10&minY=0&maxX=56&maxY=23&width=56&height=23";
        sourceEntry = buildMipmapEntry(
                "file:///Merlin-6257_21-05-20_125416.uint8.h5?dataSet=0-0-0.mipmap.0&z=0",
                ImageLoader.LoaderType.H5_SLICE,
                baseMaskUrl,
                ImageLoader.LoaderType.DYNAMIC_MASK);

        derivedImageAndMask = mipmapPathBuilder.deriveImageAndMask(mipmapLevel, sourceEntry, false).getValue();

        expectedMaskUrl = baseMaskUrl + "&level=" + mipmapLevel;
        Assert.assertEquals("invalid derived maskUrl for " + sourceEntry.getValue(),
                            expectedMaskUrl, derivedImageAndMask.getMaskUrl());
    }

    private Map.Entry<Integer, ImageAndMask> buildMipmapEntry(final String imageUrl,
                                                              final ImageLoader.LoaderType imageLoaderType,
                                                              final String maskUrl,
                                                              final ImageLoader.LoaderType maskLoaderType) {
        final ImageAndMask sourceImageAndMask = new ImageAndMask(imageUrl,
                                                                 imageLoaderType,
                                                                 0,
                                                                 maskUrl,
                                                                 maskLoaderType,
                                                                 null);
        final ChannelSpec channelSpec = new ChannelSpec();
        channelSpec.putMipmap(0, sourceImageAndMask);
        return channelSpec.getFirstMipmapEntry();
    }
}
