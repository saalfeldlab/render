package org.janelia.alignment.multisem;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.trakem2.transform.TranslationModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.loader.MultiBoxDynamicMaskLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;

/**
 * Identifies an MFOV in a specific z-layer.
 *
 * @author Eric Trautman
 */
public class LayerMFOV
        implements Comparable<LayerMFOV>, Serializable {

    private final double z;
    private final String name;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private LayerMFOV() {
        this(0.0, "");
    }

    public LayerMFOV(final double z,
                     final String name) {
        this.z = z;
        this.name = name;
    }

    public double getZ() {
        return z;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(final Object that) {
        if (this == that) {
            return true;
        }
        if (that == null || getClass() != that.getClass()) {
            return false;
        }
        final LayerMFOV layerMfov = (LayerMFOV) that;
        return Double.compare(this.z, layerMfov.z) == 0 && this.name.equals(layerMfov.name);
    }

    @Override
    public int hashCode() {
        int result;
        result = Double.hashCode(z);
        result = 31 * result + name.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "z_" + z + "_mfov_" + name;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<LayerMFOV> JSON_HELPER =
            new JsonUtils.Helper<>(LayerMFOV.class);

    @Override
    public int compareTo(final LayerMFOV that) {
        int result = Double.compare(this.z, that.z);
        if (result == 0) {
            result = this.name.compareTo(that.name);
        }
        return result;
    }

    /**
     * @param  baseDataUrl  the base URL for the render web service.
     * @param  stackId      stack identifier.
     * @param  renderScale  scale to apply to each SFOV within each MFOV when the MFOVs images are later rendered.
     *
     * @return a URL string to retrieve the render parameters for this MFOV.
     *
     * @throws IllegalArgumentException
     *   if this LayerMFOV's name is not a simple MFOV name.
     */
    public String buildRenderParametersUrl(final String baseDataUrl,
                                           final StackId stackId,
                                           final double renderScale)
            throws IllegalArgumentException {

        if (! MultiSemUtilities.isSimpleMFOVName(name)) {
            throw new IllegalArgumentException("LayerMFOV name '" + name + "' must be a simple name like 'm0012'");
        }

        final String stackUrl = baseDataUrl + "/owner/" + stackId.getOwner() +
                                "/project/" + stackId.getProject() + "/stack/" + stackId.getStack();
        return stackUrl + "/z/" + this.z + "/render-parameters?tileIdPattern=_" + this.name + "_&scale=" + renderScale;
    }

    /**
     * @param  baseDataUrl  the base URL for the render web service.
     * @param  stackId      stack identifier.
     * @param  renderScale  scale to apply to each SFOV within each MFOV when the MFOVs images are later rendered.
     * @param  rowIndex     row index of the MFOV in the grid of MFOVs.
     * @param  columnIndex  column index of the MFOV in the grid of MFOVs.
     *
     * @return a TileSpec for this LayerMFOV with the specified row and column index.
     *
     * @throws IllegalArgumentException
     *   if this LayerMFOV's name is not a simple MFOV name.
     */
    public TileSpec buildTileSpec(final String baseDataUrl,
                                  final StackId stackId,
                                  final double renderScale,
                                  final int rowIndex,
                                  final int columnIndex)
            throws IllegalArgumentException {

        final String renderParametersUrl = buildRenderParametersUrl(baseDataUrl, stackId, renderScale);
        final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrl);

        final int scaledImageWidth = (int) Math.floor(renderParameters.width * renderScale);
        final int scaledImageHeight = (int) Math.floor(renderParameters.height * renderScale);
        final double x = renderParameters.x * renderScale;
        final double y = renderParameters.y * renderScale;
        final Double z = this.getZ();

        final TileSpec tileSpec = new TileSpec();

        tileSpec.setTileId(this.toString());
        tileSpec.setZ(z);
        tileSpec.setWidth((double) scaledImageWidth);
        tileSpec.setHeight((double) scaledImageHeight);

        final LayoutData layoutData = new LayoutData(z.toString(), null, null,
                                                     rowIndex, columnIndex, x, y, null);
        tileSpec.setLayout(layoutData);

        final ChannelSpec channelSpec = new ChannelSpec();
        final MultiBoxDynamicMaskLoader.MultiBoxDynamicMaskDescription maskDescription =
                MultiBoxDynamicMaskLoader.buildMaskDescription(renderParameters,
                                                               renderScale,
                                                               scaledImageWidth,
                                                               scaledImageHeight);

        // Convert
        //   http://.../stack/w60_s360_r00_gc/z/1.0/render-parameters?tileIdPattern=_m0037_&scale=0.1
        // to:
        //   http://.../stack/w60_s360_r00_gc/z/1.0/png-image?tileIdPattern=_m0037_&scale=0.1&maxTileSpecsToRender=91
        final String pngImageUrl = renderParametersUrl.replace("render-parameters", "png-image") +
                                   "&maxTileSpecsToRender=" + MultiSemUtilities.NUMBER_OF_TILES_IN_MFOV;

        channelSpec.putMipmap(0,
                              new ImageAndMask(pngImageUrl,
                                               null,
                                               null,
                                               maskDescription.toString(),
                                               ImageLoader.LoaderType.MULTI_BOX_DYNAMIC_MASK,
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

    public static Map<Double, Set<String>> buildZToMFOVNamesMap(final List<LayerMFOV> layerMFOVList) {
        final Map<Double, Set<String>> zToMFOVNamesMap = new HashMap<>();
        for (final LayerMFOV layerMFOV : layerMFOVList) {
            final Set<String> layerMFOVNames = zToMFOVNamesMap.computeIfAbsent(layerMFOV.getZ(), z -> new HashSet<>());
            layerMFOVNames.add(layerMFOV.getName());
        }
        return zToMFOVNamesMap;
    }
}
