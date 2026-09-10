package org.janelia.alignment.multisem;

import java.util.Collections;

import mpicbg.trakem2.transform.TranslationModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;

/**
 * Utilities for rendering a z-layer.
 */
public class Layer {


    /**
     * @return w61_s071_r00_gc_icc_par_asoi_z002_scan005 for stack w61_s071_r00_gc_icc_par_asoi,
     *         z 2, and scan name scan005.
     */
    public static String toLayerAsTileName(final String stackName,
                                           final int z,
                                           final String scanName) {
        return String.format("%s_z%03d_%s", stackName, z, scanName);
    }

    /**
     * @param  baseDataUrl  the base URL for the render web service.
     * @param  stackId      stack identifier.
     * @param  z            layer z value.
     * @param  renderScale  scale to apply to each SFOV within each MFOV when the MFOVs images are later rendered.
     *
     * @return a URL string to retrieve the render parameters for this MFOV.
     */
    public static String buildRenderParametersUrl(final String baseDataUrl,
                                                  final StackId stackId,
                                                  final Double z,
                                                  final double renderScale) {
        final String stackUrl = baseDataUrl + "/owner/" + stackId.getOwner() +
                                "/project/" + stackId.getProject() + "/stack/" + stackId.getStack();
        return stackUrl + "/z/" + z + "/render-parameters?scale=" + renderScale;
    }

    /**
     * @param  baseDataUrl  the base URL for the render web service.
     * @param  stackId      stack identifier.
     * @param  z            layer z value.
     * @param  renderScale  scale to apply to each SFOV within each MFOV when the MFOVs images are later rendered.
     *
     * @return a TileSpec for this z layer.
     */
    public static TileSpec buildTileSpec(final String baseDataUrl,
                                         final StackId stackId,
                                         final Double z,
                                         final double renderScale) {

        final String renderParametersUrl = buildRenderParametersUrl(baseDataUrl, stackId, z, renderScale);
        final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrl);

        final int numberOfTilesInZLayer = renderParameters.numberOfTileSpecs();

        // all SFOVs in a z layer come from the same scan, so the first one identifies the layer's scan
        final TileSpec firstSfovTileSpec = renderParameters.getTileSpecs().get(0);
        final String scanName = MultiSemUtilities.getScanStringForTileId(firstSfovTileSpec.getTileId());

        final double scaledImageWidth = Math.floor(renderParameters.width * renderScale);
        final double scaledImageHeight = Math.floor(renderParameters.height * renderScale);
        final double x = renderParameters.x * renderScale;
        final double y = renderParameters.y * renderScale;

        final TileSpec tileSpec = new TileSpec();

        tileSpec.setTileId(toLayerAsTileName(stackId.getStack(), z.intValue(), scanName));
        tileSpec.setZ(z);
        tileSpec.setWidth(scaledImageWidth);
        tileSpec.setHeight(scaledImageHeight);

        final LayoutData layoutData = new LayoutData(z.toString(), null, null,
                                                     0, 0, x, y, null);
        tileSpec.setLayout(layoutData);

        final ChannelSpec channelSpec = new ChannelSpec();
        // Convert
        //   http://.../stack/w60_s360_r00_gc/z/1.0/render-parameters?scale=0.1
        // to:
        //   http://.../stack/w60_s360_r00_gc/z/1.0/png-image?scale=0.1&maxTileSpecsToRender=1234
        final String pngImageUrl = renderParametersUrl.replace("render-parameters", "png-image") +
                                   "&maxTileSpecsToRender=" + numberOfTilesInZLayer;

        final ImageAndMask firstSfovImageAndMask = firstSfovTileSpec.getFirstMipmapEntry().getValue();
        final ImageLoader.LoaderType firstSfovImageLoaderType = firstSfovImageAndMask.getImageLoaderType();
        ImageLoader.LoaderType loaderType = null;
        if (ImageLoader.LoaderType.IMAGEJ_DEFAULT_W_TIMEOUT.equals(firstSfovImageLoaderType)) {
            // If the first SFOV uses LoaderType.IMAGEJ_DEFAULT_W_TIMEOUT
            // (e.g., when source images are stored in Google buckets),
            // use the same loader type for the MFOV image.
            loaderType = ImageLoader.LoaderType.IMAGEJ_DEFAULT_W_TIMEOUT;
        }

        channelSpec.putMipmap(0,
                              new ImageAndMask(pngImageUrl,
                                               loaderType,
                                               null,
                                               null,
                                               null,
                                               null));
        tileSpec.addChannel(channelSpec);
        tileSpec.convertSingleChannelSpecToLegacyForm();

        final String translateDataString = (int) Math.floor(x) + " " + (int) Math.floor(y);
        final LeafTransformSpec transformSpec = new LeafTransformSpec(TranslationModel2D.class.getName(),
                                                                      translateDataString);
        tileSpec.addTransformSpecs(Collections.singletonList(transformSpec));

        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

        return tileSpec;
    }

}
