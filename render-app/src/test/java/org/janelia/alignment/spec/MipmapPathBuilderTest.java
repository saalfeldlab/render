package org.janelia.alignment.spec;

import java.util.Map;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

        assertNotNull(json, "json generation returned null string");

        final MipmapPathBuilder parsedBuilder = MipmapPathBuilder.fromJson(json);
        assertNotNull(parsedBuilder, "null builder returned from json parse");
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
        assertEquals(expectedImageUrl, derivedImageAndMask.getImageUrl(),
                     "invalid derived imageUrl for " + sourceEntry.getValue());

        String expectedMaskUrl = "file:/mipmaps/" + mipmapLevel + "/masks/test-mask.png.tif";
        assertEquals(expectedMaskUrl, derivedImageAndMask.getMaskUrl(),
                     "invalid derived maskUrl for " + sourceEntry.getValue());


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
        assertEquals(expectedImageUrl, derivedImageAndMask.getImageUrl(),
                     "invalid derived imageUrl for " + sourceEntry.getValue());

        assertEquals(ImageLoader.LoaderType.H5_SLICE, derivedImageAndMask.getImageLoaderType(),
                     "invalid derived imageLoaderType for " + sourceEntry.getValue());

        expectedMaskUrl = "file:/mipmaps/" + mipmapLevel + "/masks/test-another-mask.png.tif";
        assertEquals(expectedMaskUrl, derivedImageAndMask.getMaskUrl(),
                     "invalid derived maskUrl for " + sourceEntry.getValue());

        final String baseMaskUrl = "mask://outside-box?minX=10&minY=0&maxX=56&maxY=23&width=56&height=23";
        sourceEntry = buildMipmapEntry(
                "file:///Merlin-6257_21-05-20_125416.uint8.h5?dataSet=0-0-0.mipmap.0&z=0",
                ImageLoader.LoaderType.H5_SLICE,
                baseMaskUrl,
                ImageLoader.LoaderType.DYNAMIC_MASK);

        derivedImageAndMask = mipmapPathBuilder.deriveImageAndMask(mipmapLevel, sourceEntry, false).getValue();

        expectedMaskUrl = baseMaskUrl + "&level=" + mipmapLevel;
        assertEquals(expectedMaskUrl, derivedImageAndMask.getMaskUrl(),
                     "invalid derived maskUrl for " + sourceEntry.getValue());
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
