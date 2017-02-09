/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.filter.NormalizeLocalContrast;
import org.janelia.alignment.filter.ValueToNoise;
import org.janelia.alignment.mapper.MultiChannelMapper;
import org.janelia.alignment.mapper.MultiChannelWithAlphaMapper;
import org.janelia.alignment.mapper.MultiChannelWithBinaryMaskMapper;
import org.janelia.alignment.mapper.PixelMapper;
import org.janelia.alignment.mapper.SingleChannelMapper;
import org.janelia.alignment.mapper.SingleChannelWithAlphaMapper;
import org.janelia.alignment.mapper.SingleChannelWithBinaryMaskMapper;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Render a set of image tile as an ARGB image.
 * <p/>
 * <pre>
 * Usage: java [-options] -cp render.jar org.janelia.alignment.RenderTile [options]
 * Options:
 *       --height
 *      Target image height
 *      Default: 256
 *       --help
 *      Display this note
 *      Default: false
 * *     --res
 *      Mesh resolution, specified by the desired size of a triangle in pixels
 *       --in
 *      Path to the input image if any
 *       --out
 *      Path to the output image
 * *     --tile_spec_url
 *      URL to JSON tile spec
 *       --width
 *      Target image width
 *      Default: 256
 * *     --x
 *      Target image left coordinate
 *      Default: 0
 * *     --y
 *      Target image top coordinate
 *      Default: 0
 * </pre>
 * <p>E.g.:</p>
 * <pre>java -cp render.jar org.janelia.alignment.RenderTile \
 *   --tile_spec_url "file://absolute/path/to/tile-spec.json" \
 *   --out "/absolute/path/to/output.png" \
 *   --x 16536
 *   --y 32
 *   --width 1024
 *   --height 1024
 *   --res 64</pre>
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class Render {

    private static final Logger LOG = LoggerFactory.getLogger(Render.class);

    /* TODO this is an adhoc filter bank for temporary use in alignment */
    final static private NormalizeLocalContrast nlcf = new NormalizeLocalContrast(500, 500, 3, true, true);
    final static private ValueToNoise vtnf1 = new ValueToNoise(0, 64, 191);
    final static private ValueToNoise vtnf2 = new ValueToNoise(255, 64, 191);
//    final static private CLAHE clahe = new CLAHE(true, 250, 256, 2);

    private Render() {
    }

    /**
     * Assemble {@link CoordinateTransform CoordinateTransforms} and add
     * bounding box offset, areaOffset, and scale for a {@link TileSpec}
     */
    public static CoordinateTransformList<CoordinateTransform> createRenderTransform(
            final TileSpec ts,
            final boolean areaOffset,
            final double scale,
            final double x,
            final double y)
    {
        final CoordinateTransformList<CoordinateTransform> ctl = new CoordinateTransformList<>();
        for (final CoordinateTransform t : ts.getTransformList().getList(null))
            ctl.add(t);
        final AffineModel2D scaleAndOffset = new AffineModel2D();
        if (areaOffset) {
            final double offset = (1 - scale) * 0.5;
            scaleAndOffset.set(scale,
                               0,
                               0,
                               scale,
                               -(x * scale + offset),
                               -(y * scale + offset));
        } else {
            scaleAndOffset.set(scale,
                               0,
                               0,
                               scale,
                               -(x * scale),
                               -(y * scale));
        }

        ctl.add(scaleAndOffset);

        return ctl;
    }

    public static void render(final RenderParameters params,
                              final BufferedImage targetImage,
                              final ImageProcessorCache imageProcessorCache)
            throws IllegalArgumentException {

        render(params.getTileSpecs(),
               params.getChannelNames(),
               params.getX(),
               params.getY(),
               params.getRes(params.getScale()),
               params.getScale(),
               params.isAreaOffset(),
               params.getNumberOfThreads(),
               params.skipInterpolation(),
               params.doFilter(),
               params.binaryMask(),
               params.excludeMask(),
               imageProcessorCache,
               params.getBackgroundRGBColor(),
               params.getMinIntensity(),
               params.getMaxIntensity(),
               targetImage);
    }

    public static void render(final List<TileSpec> tileSpecs,
                              final List<String> channelNames,
                              final double x,
                              final double y,
                              final double meshCellSize,
                              final double scale,
                              final boolean areaOffset,
                              final int numberOfThreads,
                              final boolean skipInterpolation,
                              final boolean doFilter,
                              final boolean binaryMask,
                              final boolean excludeMask,
                              final ImageProcessorCache imageProcessorCache,
                              final Integer backgroundRGBColor,
                              final Double renderMinIntensity,
                              final Double renderMaxIntensity,
                              final BufferedImage targetImage)
            throws IllegalArgumentException {

        final long tileLoopStart = System.currentTimeMillis();

        LOG.debug("render: entry, processing {} tile specifications, numberOfThreads={}",
                  tileSpecs.size(), numberOfThreads);

        final int targetWidth = targetImage.getWidth();
        final int targetHeight = targetImage.getHeight();

        final ChannelMap worldTargetChannels = new ChannelMap();
        for (final String channelName : channelNames) {
            worldTargetChannels.put(channelName,
                                    new ImageProcessorWithMasks(
                                            new FloatProcessor(targetWidth, targetHeight),
                                            new ByteProcessor(targetWidth, targetHeight),
                                            null));
        }

        render(tileSpecs,
               worldTargetChannels,
               x, y,
               meshCellSize, scale, areaOffset,
               numberOfThreads,
               skipInterpolation, doFilter, binaryMask, excludeMask, imageProcessorCache);

        final long drawImageStart = System.currentTimeMillis();

        final Graphics2D targetGraphics = targetImage.createGraphics();

        if (backgroundRGBColor != null) {
            targetGraphics.setBackground(new Color(backgroundRGBColor));
            targetGraphics.clearRect(0, 0, targetWidth, targetHeight);
        }

        if (tileSpecs.size() > 0) {

            // TODO: handle targetImage generation for multiple channels
            // This hack only generates the first channel even though all channels are rendered.

            final String channelName = channelNames.get(0);
            final Set<String> channelNameSet = new HashSet<>(Collections.singletonList(channelName));
            final ChannelSpec channelSpec = tileSpecs.get(0).getChannels(channelNameSet).get(0);
            final ImageProcessorWithMasks worldTarget = worldTargetChannels.get(channelName);
            final BufferedImage image = ArgbRenderer.targetToARGBImage(worldTarget,
                                                                       channelSpec.getMinIntensity(),
                                                                       channelSpec.getMaxIntensity(),
                                                                       binaryMask,
                                                                       renderMinIntensity,
                                                                       renderMaxIntensity);
            targetGraphics.drawImage(image, 0, 0, null);
        }

        targetGraphics.dispose();

        final long drawImageStop = System.currentTimeMillis();

        LOG.debug("render: exit, {} tiles processed in {} milliseconds, draw image:{}",
                  tileSpecs.size(),
                  System.currentTimeMillis() - tileLoopStart,
                  drawImageStop - drawImageStart);


    }

    public static void render(final List<TileSpec> tileSpecs,
                              final ChannelMap targetChannels,
                              final double x,
                              final double y,
                              final double meshCellSize,
                              final double scale,
                              final boolean areaOffset,
                              final int numberOfThreads,
                              final boolean skipInterpolation,
                              final boolean doFilter,
                              final boolean binaryMask,
                              final boolean excludeMask,
                              final ImageProcessorCache imageProcessorCache)
            throws IllegalArgumentException {

        int tileSpecIndex = 0;
        PixelMapper tilePixelMapper;
        long tileSpecStart;
        long loadMipStop;
        long filterStop;
        long loadMaskStop;
        long ctListCreationStop;
        long meshCreationStop;
        long sourceCreationStop;
        long targetCreationStop;
        long mapInterpolatedStop;

        for (final TileSpec ts : tileSpecs) {
            tileSpecStart = System.currentTimeMillis();

            final List<ChannelSpec> channelSpecList = ts.getChannels(targetChannels.names());

            if (channelSpecList.size() == 0) {
                LOG.debug("skipping tile '{}' because it does not have any channels with the names {}",
                          ts.getTileId(),
                          targetChannels.names());
                continue;
            }

            final ChannelSpec firstChannel = channelSpecList.get(0);
            final ImageProcessorWithMasks target = targetChannels.get(firstChannel.getName());

            final CoordinateTransformList<CoordinateTransform> ctl = createRenderTransform(ts, areaOffset, scale, x, y);

            Map.Entry<Integer, ImageAndMask> mipmapEntry;
            ImageAndMask imageAndMask = null;
            ImageProcessor widthAndHeightProcessor = null;
            int width = ts.getWidth();
            int height = ts.getHeight();
            // if width and height were not set, figure width and height
            if ((width < 0) || (height < 0)) {
                mipmapEntry = firstChannel.getFirstMipmapEntry();
                imageAndMask = mipmapEntry.getValue();
                widthAndHeightProcessor = imageProcessorCache.get(imageAndMask.getImageUrl(), 0, false);
                width = widthAndHeightProcessor.getWidth();
                height = widthAndHeightProcessor.getHeight();
            }

            // estimate average scale
            final double s = Utils.sampleAverageScale(ctl, width, height, meshCellSize);
            int mipmapLevel = Utils.bestMipmapLevel(s);

            int downSampleLevels = 0;
            final ImageProcessor ipMipmap;
            if (widthAndHeightProcessor == null) { // width and height were explicitly specified as parameters

                mipmapEntry = firstChannel.getFloorMipmapEntry(mipmapLevel);
                imageAndMask = mipmapEntry.getValue();

                final int currentMipmapLevel = mipmapEntry.getKey();
                if (currentMipmapLevel < mipmapLevel) {
                    downSampleLevels = mipmapLevel - currentMipmapLevel;
                } else {
                    mipmapLevel = currentMipmapLevel;
                }

                ipMipmap = imageProcessorCache.get(imageAndMask.getImageUrl(), downSampleLevels, false);

            } else if (mipmapLevel > 0) {

                downSampleLevels = mipmapLevel;
                ipMipmap = imageProcessorCache.get(imageAndMask.getImageUrl(), downSampleLevels, false);

            } else {

                ipMipmap = widthAndHeightProcessor;
            }

            loadMipStop = System.currentTimeMillis();

            if (ipMipmap.getWidth() == 0 || ipMipmap.getHeight() == 0) {
                LOG.debug("Skipping zero pixel size mipmap {}", imageAndMask.getImageUrl());
                continue;
            }

            // filter
            if (doFilter) {
                final double mipmapScale = 1.0 / (1 << mipmapLevel);
                vtnf1.process(ipMipmap, mipmapScale);
                vtnf2.process(ipMipmap, mipmapScale);
                nlcf.process(ipMipmap, mipmapScale);
            }

            filterStop = System.currentTimeMillis();

            // open mask
            ImageProcessor maskSourceProcessor;
            final String maskUrl = imageAndMask.getMaskUrl();
            if ((maskUrl != null) && (! excludeMask)) {
                maskSourceProcessor = imageProcessorCache.get(maskUrl, downSampleLevels, true);
            } else {
                maskSourceProcessor = null;
            }

            loadMaskStop = System.currentTimeMillis();

            // attach mipmap transformation
            final CoordinateTransformList<CoordinateTransform> ctlMipmap = new CoordinateTransformList<>();
            ctlMipmap.add(Utils.createScaleLevelTransform(mipmapLevel));
            ctlMipmap.add(ctl);

            ctListCreationStop = System.currentTimeMillis();

            // create mesh
            final RenderTransformMesh mesh = new RenderTransformMesh(
                    ctlMipmap,
                    (int) (width / meshCellSize + 0.5),
                    ipMipmap.getWidth(),
                    ipMipmap.getHeight());

            mesh.updateAffines();

            meshCreationStop = System.currentTimeMillis();

            final ImageProcessorWithMasks source = new ImageProcessorWithMasks(ipMipmap, maskSourceProcessor, null);

            // if source.mask gets "quietly" removed (because of size), we need to also remove maskSourceProcessor
            if ((maskSourceProcessor != null) && (source.mask == null)) {
                LOG.warn("render: removing mask because ipMipmap and maskSourceProcessor differ in size, ipMipmap: " +
                         ipMipmap.getWidth() + "x" + ipMipmap.getHeight() + ", maskSourceProcessor: " +
                         maskSourceProcessor.getWidth() + "x" + maskSourceProcessor.getHeight());
                maskSourceProcessor = null;
            }

            sourceCreationStop = System.currentTimeMillis();

            if (channelSpecList.size() > 1)  {

                final ChannelMap sourceChannels = new ChannelMap();
                sourceChannels.put(firstChannel.getName(), source);

                loadAdditionalSourceChannels(channelSpecList,
                                             sourceChannels,
                                             ipMipmap.getWidth(),
                                             ipMipmap.getHeight(),
                                             mipmapLevel,
                                             excludeMask,
                                             imageProcessorCache);

                if (maskSourceProcessor != null) {
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

                if (maskSourceProcessor != null) {
                    if (binaryMask) {
                        tilePixelMapper = new SingleChannelWithBinaryMaskMapper(source, target, (!skipInterpolation));
                    } else {
                        tilePixelMapper =
                                new SingleChannelWithAlphaMapper(source, target, (!skipInterpolation));
                    }
                } else {
                    tilePixelMapper = new SingleChannelMapper(source, target, (!skipInterpolation));
                }

            }

            targetCreationStop = System.currentTimeMillis();

            final RenderTransformMeshMappingWithMasks mapping = new RenderTransformMeshMappingWithMasks(mesh);

            final String mapType = skipInterpolation ? "" : " interpolated";
            mapping.map(tilePixelMapper, numberOfThreads);

            mapInterpolatedStop = System.currentTimeMillis();

            LOG.debug("render: tile {} took {} milliseconds to process (load mip:{}, downSampleLevels:{}, filter:{}, load mask:{}, ctList:{}, mesh:{}, source:{}, target:{}, map{}:{}), cacheSize:{}",
                      tileSpecIndex,
                      mapInterpolatedStop - tileSpecStart,
                      loadMipStop - tileSpecStart,
                      downSampleLevels,
                      filterStop - loadMipStop,
                      loadMaskStop - filterStop,
                      ctListCreationStop - loadMaskStop,
                      meshCreationStop - ctListCreationStop,
                      sourceCreationStop - meshCreationStop,
                      targetCreationStop - sourceCreationStop,
                      mapType,
                      mapInterpolatedStop - targetCreationStop,
                      imageProcessorCache.size());

            tileSpecIndex++;
        }

    }

    private static void loadAdditionalSourceChannels(final List<ChannelSpec> channelSpecList,
                                                     final ChannelMap sourceChannels,
                                                     final int scaledWidth,
                                                     final int scaledHeight,
                                                     final int mipmapLevel,
                                                     final boolean excludeMask,
                                                     final ImageProcessorCache imageProcessorCache) {

        // TODO: need to figure out how to handle different min/max intensity per channel

        for (int i = 1; i < channelSpecList.size(); i++) {

            final ChannelSpec channelSpec = channelSpecList.get(i);
            final Map.Entry<Integer, ImageAndMask> mipmapEntry =
                    channelSpec.getFloorMipmapEntry(mipmapLevel);
            final ImageAndMask imageAndMask = mipmapEntry.getValue();

            int downSampleLevels = 0;
            final int currentMipmapLevel = mipmapEntry.getKey();
            if (currentMipmapLevel < mipmapLevel) {
                downSampleLevels = mipmapLevel - currentMipmapLevel;
            }

            final ImageProcessor ip = imageProcessorCache.get(imageAndMask.getImageUrl(), downSampleLevels, false);

            if (ip.getWidth() == scaledWidth && ip.getWidth() == scaledHeight) {

                // TODO: does it make sense to support the canned filter for secondary channels?

                // open mask
                final ImageProcessor mask;
                final String maskUrl = imageAndMask.getMaskUrl();
                if ((maskUrl != null) && (!excludeMask)) {
                    mask = imageProcessorCache.get(maskUrl, downSampleLevels, true);
                } else {
                    mask = null;
                }

                sourceChannels.put(channelSpec.getName(), new ImageProcessorWithMasks(ip, mask, null));

            } else {

                LOG.debug("skipping mipmap because level {} dimensions ({}x{}) differ from primary channel ({}x{}) for {}",
                          mipmapLevel,
                          ip.getWidth(), ip.getHeight(),
                          scaledWidth, scaledHeight,
                          imageAndMask.getImageUrl());
            }
        }

    }

    public static void renderUsingCommandLineArguments(final String[] args)
            throws Exception {

        final long mainStart = System.currentTimeMillis();
        long parseStop = mainStart;
        long targetOpenStop = mainStart;
        long saveStart = mainStart;
        long saveStop = mainStart;

        final RenderParameters params = RenderParameters.parseCommandLineArgs(args);

        if (params.displayHelp()) {

            params.showUsage();

        } else {

            LOG.info("renderUsingCommandLineArguments: entry, params={}", params);

            params.validate();

            parseStop = System.currentTimeMillis();

            final BufferedImage targetImage = params.openTargetImage();

            targetOpenStop = System.currentTimeMillis();

            final ImageProcessorCache imageProcessorCache = new ImageProcessorCache();
            render(params,
                   targetImage,
                   imageProcessorCache);

            saveStart = System.currentTimeMillis();

            // save the modified image
            final String outputPathOrUri = params.getOut();
            final String outputFormat = outputPathOrUri.substring(outputPathOrUri.lastIndexOf('.') + 1);
            Utils.saveImage(targetImage,
                            outputPathOrUri,
                            outputFormat,
                            params.isConvertToGray(),
                            params.getQuality());

            saveStop = System.currentTimeMillis();
        }

        LOG.debug("renderUsingCommandLineArguments: processing took {} milliseconds (parse command:{}, open target:{}, render tiles:{}, save target:{})",
                  saveStop - mainStart,
                  parseStop - mainStart,
                  targetOpenStop - parseStop,
                  saveStart - targetOpenStop,
                  saveStop - saveStart);

    }

    public static void main(final String[] args) {

        try {
            renderUsingCommandLineArguments(args);
        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
            System.exit(1);
        }

    }

}
