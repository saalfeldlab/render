package org.janelia.alignment.mipmap;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.RenderTransformMesh;
import org.janelia.alignment.RenderTransformMeshMappingWithMasks;
import org.janelia.alignment.TransformableCanvas;
import org.janelia.alignment.Utils;
import org.janelia.alignment.mapper.MultiChannelMapper;
import org.janelia.alignment.mapper.MultiChannelWithAlphaMapper;
import org.janelia.alignment.mapper.MultiChannelWithBinaryMaskMapper;
import org.janelia.alignment.mapper.PixelMapper;
import org.janelia.alignment.mapper.SingleChannelMapper;
import org.janelia.alignment.mapper.SingleChannelWithAlphaMapper;
import org.janelia.alignment.mapper.SingleChannelWithBinaryMaskMapper;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link MipmapSource} implementation that renders a canvas composed from
 * a list of other {@link TransformableCanvas transformed sources}.
 *
 * @author Stephan Saalfeld
 * @author Eric Trautman
 */
public class CanvasMipmapSource
        implements MipmapSource {

    private final String canvasName;
    private final List<String> channelNames;
    private final List<TransformableCanvas> canvasList;
    private final double x;
    private final double y;
    private final int fullScaleWidth;
    private final int fullScaleHeight;
    private final double meshCellSize;
    private final double levelZeroScale;
    private final boolean areaOffset;
    private final int numberOfMappingThreads;
    private final boolean skipInterpolation;
    private final boolean binaryMask;

    /**
     * Constructs a canvas based upon {@link RenderParameters} that is dynamically
     * rendered when {@link #getChannels} is called.
     *
     * @param  renderParameters     parameters specifying tiles, transformations, and render context.
     * @param  imageProcessorCache  cache of previously loaded pixel data (or null if caching is not desired).
     */
    public CanvasMipmapSource(final RenderParameters renderParameters,
                              final ImageProcessorCache imageProcessorCache) {

        this("canvas",
             renderParameters.getChannelNames(),
             buildCanvasList(renderParameters, imageProcessorCache),
             renderParameters.getX(),
             renderParameters.getY(),
             renderParameters.getWidth(),
             renderParameters.getHeight(),
             renderParameters.getRes(renderParameters.getScale()),
             renderParameters.getScale(),
             renderParameters.isAreaOffset(),
             renderParameters.getNumberOfThreads(),
             renderParameters.skipInterpolation(),
             renderParameters.binaryMask());
    }

    /**
     * Constructs a canvas composed of {@link TransformableCanvas transformed sources}
     * that is dynamically rendered when {@link #getChannels} is called.
     *
     * @param  canvasName              name of this canvas.
     * @param  channelNames            names of channels to include in this canvas.
     * @param  canvasList              list of transformed components to render.
     * @param  x                       left coordinate for this canvas.
     * @param  y                       top coordinate for this canvas.
     * @param  fullScaleWidth          canvas width at mipmap level 0.
     * @param  fullScaleHeight         canvas height at mipmap level 0.
     * @param  meshCellSize            desired size of a mesh cell (triangle) in pixels.
     * @param  levelZeroScale          scale factor for transformed components at mipmap level 0 of this canvas.
     * @param  areaOffset              add bounding box offset.
     * @param  numberOfMappingThreads  number of threads to use for pixel mapping.
     * @param  skipInterpolation       enable sloppy but fast rendering by skipping interpolation.
     * @param  binaryMask              render only 100% opaque pixels.
     */
    public CanvasMipmapSource(final String canvasName,
                              final List<String> channelNames,
                              final List<TransformableCanvas> canvasList,
                              final double x,
                              final double y,
                              final int fullScaleWidth,
                              final int fullScaleHeight,
                              final double meshCellSize,
                              final double levelZeroScale,
                              final boolean areaOffset,
                              final int numberOfMappingThreads,
                              final boolean skipInterpolation,
                              final boolean binaryMask) {
        this.canvasName = canvasName;
        this.channelNames = channelNames;
        this.canvasList = canvasList;
        this.x = x;
        this.y = y;
        this.fullScaleWidth = fullScaleWidth;
        this.fullScaleHeight = fullScaleHeight;
        this.meshCellSize = meshCellSize;
        this.levelZeroScale = levelZeroScale;
        this.areaOffset = areaOffset;
        this.numberOfMappingThreads = numberOfMappingThreads;
        this.skipInterpolation = skipInterpolation;
        this.binaryMask = binaryMask;
    }

    @Override
    public String getSourceName() {
        return canvasName;
    }

    @Override
    public int getFullScaleWidth() {
        return fullScaleWidth;
    }

    @Override
    public int getFullScaleHeight() {
        return fullScaleHeight;
    }

    @Override
    public Map<String, ImageProcessorWithMasks> getChannels(final int mipmapLevel)
            throws IllegalArgumentException {

        final Map<String, ImageProcessorWithMasks> targetChannels = new HashMap<>(channelNames.size());

        final double levelScale = (1.0 / Math.pow(2.0, mipmapLevel)) * levelZeroScale;
        final int levelWidth = (int) ((fullScaleWidth * levelScale) + 0.5);
        final int levelHeight = (int) ((fullScaleHeight * levelScale) + 0.5);

        for (final String channelName : channelNames) {
            targetChannels.put(channelName,
                               new ImageProcessorWithMasks(
                                       new FloatProcessor(levelWidth, levelHeight),
                                       new ByteProcessor(levelWidth, levelHeight),
                                       null));
        }

        for (final TransformableCanvas canvas : canvasList) {

            final CoordinateTransformList<CoordinateTransform> renderTransformList =
                    createRenderTransformList(canvas.getTransformList(), areaOffset, levelScale, x, y);

            final MipmapSource source = canvas.getSource();

            final double averageScale = Utils.sampleAverageScale(renderTransformList,
                                                                 source.getFullScaleWidth(),
                                                                 source.getFullScaleHeight(),
                                                                 meshCellSize);

            final int componentMipmapLevel = Utils.bestMipmapLevel(averageScale);

            mapPixels(source,
                      componentMipmapLevel,
                      renderTransformList,
                      meshCellSize,
                      binaryMask,
                      numberOfMappingThreads,
                      skipInterpolation,
                      targetChannels);
        }

        return targetChannels;
    }

    /**
     * @return a list of {@link TransformableCanvas} objects for the specified parameters.
     */
    public static List<TransformableCanvas> buildCanvasList(final RenderParameters renderParameters,
                                                            final ImageProcessorCache imageProcessorCache) {
        final Set<String> channelNames = new HashSet<>(renderParameters.getChannelNames());

        final List<TransformableCanvas> canvasList = new ArrayList<>(renderParameters.numberOfTileSpecs());

        MipmapSource source;
        for (final TileSpec tileSpec : renderParameters.getTileSpecs()) {

            source = new UrlMipmapSource("tile '" + tileSpec.getTileId() + "'",
                                         tileSpec.getWidth(),
                                         tileSpec.getHeight(),
                                         tileSpec.getChannels(channelNames),
                                         renderParameters.excludeMask(),
                                         imageProcessorCache);

            if (renderParameters.doFilter()) {
                source = new FilteredMipmapSource("filtered " + source.getSourceName(),
                                                  source,
                                                  FilteredMipmapSource.getDefaultFilters());
            }

            canvasList.add(new TransformableCanvas(source, tileSpec.getTransforms().getNewInstanceAsList()));
        }

        return canvasList;
    }

    /**
     * Creates a transform list that includes all transforms for a canvas plus an additional render context
     * transform for bounding box offset, scale, and (optionally) an area offset.
     *
     * @param  canvasTransformList  list of transforms for full scale (and un-clipped) canvas.
     * @param  areaOffset           add bounding box offset.
     * @param  scale                scale factor applied to the target image.
     * @param  x                    target image left coordinate.
     * @param  y                    target image top coordinate.
     *
     * @return transform list for a specific render context.
     */
    public static CoordinateTransformList<CoordinateTransform> createRenderTransformList(
            final CoordinateTransformList<CoordinateTransform> canvasTransformList,
            final boolean areaOffset,
            final double scale,
            final double x,
            final double y) {

        final CoordinateTransformList<CoordinateTransform> renderTransformList = new CoordinateTransformList<>();

        for (final CoordinateTransform t : canvasTransformList.getList(null)) {
            renderTransformList.add(t);
        }

        final AffineModel2D scaleAndOffset = new AffineModel2D();

        if (areaOffset) {

            final double offset = (1 - scale) * 0.5;
            scaleAndOffset.set(scale,
                               0,
                               0,
                               scale,
                               -(x * scale + offset),
                               -(y * scale + offset));
            renderTransformList.add(scaleAndOffset);

        } else {

            scaleAndOffset.set(scale,
                               0,
                               0,
                               scale,
                               -(x * scale),
                               -(y * scale));
            renderTransformList.add(scaleAndOffset);

        }

        return renderTransformList;
    }

    /**
     * Creates a mesh that incorporates a scale transform based upon the mipmap level
     * along with the transforms for the render context.
     *
     * @param  mipmapLevel          source mipmap level.
     * @param  renderTransformList  list of transforms for the render context.
     * @param  fullScaleWidth       full scale width of the source.
     * @param  meshCellSize         desired size of a mesh cell (triangle) in pixels.
     * @param  mipmapWidth          width of the source mipmap.
     * @param  mipmapHeight         height of the source mipmap.
     *
     * @return mesh for mapping pixels.
     */
    public static RenderTransformMesh createRenderMesh(final int mipmapLevel,
                                                       final CoordinateTransformList<CoordinateTransform> renderTransformList,
                                                       final int fullScaleWidth,
                                                       final double meshCellSize,
                                                       final int mipmapWidth,
                                                       final int mipmapHeight) {

        // attach mipmap transformation
        final CoordinateTransformList<CoordinateTransform> mipmapLevelTransformList = new CoordinateTransformList<>();
        mipmapLevelTransformList.add(Utils.createScaleLevelTransform(mipmapLevel));
        mipmapLevelTransformList.add(renderTransformList);

        // create mesh
        final RenderTransformMesh mesh = new RenderTransformMesh(
                mipmapLevelTransformList,
                (int) (fullScaleWidth / meshCellSize + 0.5),
                mipmapWidth,
                mipmapHeight);

        mesh.updateAffines();

        return mesh;
    }

    /**
     * Maps pixels from a source to a target.
     *
     * @param  source                  source pixel data.
     * @param  mipmapLevel             source mipmap level.
     * @param  renderTransformList     list of transforms for the render context.
     * @param  meshCellSize            desired size of a mesh cell (triangle) in pixels.
     * @param  binaryMask              render only 100% opaque pixels.
     * @param  numberOfMappingThreads  number of threads to use for pixel mapping.
     * @param  skipInterpolation       enable sloppy but fast rendering by skipping interpolation.
     * @param  targetChannels          target channels for mapped results.
     */
    public static void mapPixels(final MipmapSource source,
                                 final int mipmapLevel,
                                 final CoordinateTransformList<CoordinateTransform> renderTransformList,
                                 final double meshCellSize,
                                 final boolean binaryMask,
                                 final int numberOfMappingThreads,
                                 final boolean skipInterpolation,
                                 final Map<String, ImageProcessorWithMasks> targetChannels) {

        final Map<String, ImageProcessorWithMasks> sourceChannels = source.getChannels(mipmapLevel);

        if (sourceChannels.size() > 0) {

            final long mapStart = System.currentTimeMillis();

            boolean hasMask = false;
            int mipmapWidth = 0;
            int mipmapHeight = 0;
            //noinspection LoopStatementThatDoesntLoop
            for (final ImageProcessorWithMasks sourceChannel : sourceChannels.values()) {
                hasMask = (sourceChannel.mask != null);
                mipmapWidth = sourceChannel.ip.getWidth();
                mipmapHeight = sourceChannel.ip.getHeight();
                break; // all channels should have same size, so we only need to look at the first channel
            }

            final PixelMapper tilePixelMapper = getPixelMapper(sourceChannels,
                                                               hasMask,
                                                               binaryMask,
                                                               skipInterpolation,
                                                               targetChannels);
            if (tilePixelMapper != null) {

                final RenderTransformMesh mesh = createRenderMesh(mipmapLevel,
                                                                  renderTransformList,
                                                                  source.getFullScaleWidth(),
                                                                  meshCellSize,
                                                                  mipmapWidth,
                                                                  mipmapHeight);

                final long meshCreationStop = System.currentTimeMillis();

                final RenderTransformMeshMappingWithMasks mapping = new RenderTransformMeshMappingWithMasks(mesh);

                final String mapType = skipInterpolation ? "" : " interpolated";
                mapping.map(tilePixelMapper, numberOfMappingThreads);

                final long mapStop = System.currentTimeMillis();

                LOG.debug("mapPixels: mapping of {} took {} milliseconds to process (mesh:{}, {}map:{})",
                          source.getSourceName(),
                          mapStop - mapStart,
                          meshCreationStop - mapStart,
                          mapType,
                          mapStop - meshCreationStop);
            }

        } else {
            LOG.warn("mapPixels: {} does not have any channels to map", source.getSourceName());
        }

    }

    /**
     * @return {@link PixelMapper} instance "optimized" for mapping source channel(s) for
     *         a specific render context.
     */
    private static PixelMapper getPixelMapper(final Map<String, ImageProcessorWithMasks> sourceChannels,
                                              final boolean hasMask,
                                              final boolean binaryMask,
                                              final boolean skipInterpolation,
                                              final Map<String, ImageProcessorWithMasks> targetChannels) {

        PixelMapper tilePixelMapper = null;

        if (sourceChannels.size() > 1) {

            if (hasMask) {
                if (binaryMask) {
                    tilePixelMapper = new MultiChannelWithBinaryMaskMapper(sourceChannels,
                                                                           targetChannels,
                                                                           (! skipInterpolation));
                } else {
                    tilePixelMapper = new MultiChannelWithAlphaMapper(sourceChannels,
                                                                      targetChannels,
                                                                      (! skipInterpolation));
                }
            } else {
                tilePixelMapper = new MultiChannelMapper(sourceChannels,
                                                         targetChannels,
                                                         (! skipInterpolation));
            }

        } else {

            //noinspection LoopStatementThatDoesntLoop
            for (final String channelName : sourceChannels.keySet()) {

                final ImageProcessorWithMasks sourceChannel = sourceChannels.get(channelName);
                final ImageProcessorWithMasks targetChannel = targetChannels.get(channelName);

                if (targetChannel != null) {

                    if (hasMask) {
                        if (binaryMask) {
                            tilePixelMapper = new SingleChannelWithBinaryMaskMapper(sourceChannel,
                                                                                    targetChannel,
                                                                                    (! skipInterpolation));
                        } else {
                            tilePixelMapper = new SingleChannelWithAlphaMapper(sourceChannel,
                                                                               targetChannel,
                                                                               (! skipInterpolation));
                        }
                    } else {
                        tilePixelMapper = new SingleChannelMapper(sourceChannel,
                                                                  targetChannel,
                                                                  (! skipInterpolation));
                    }

                } else {

                    throw new IllegalArgumentException("The sole source channel (" + channelName +
                                                       ") is missing from specified target channels (" +
                                                       targetChannels.keySet() + ").");
                }

                break; // should only be one channel
            }

        }
        return tilePixelMapper;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasMipmapSource.class);

}
