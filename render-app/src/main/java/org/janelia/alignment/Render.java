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
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.List;
import java.util.Map;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.filter.NormalizeLocalContrast;
import org.janelia.alignment.filter.ValueToNoise;
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
               targetImage,
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
               params.getMaxIntensity());
    }

    public static void render(final List<TileSpec> tileSpecs,
                              final BufferedImage targetImage,
                              final double x,
                              final double y,
                              final double meshCellSize,
                              final double scale,
                              final boolean areaOffset,
                              final int numberOfThreads,
                              final boolean skipInterpolation,
                              final boolean doFilter,
                              final ImageProcessorCache imageProcessorCache,
                              final Integer backgroundRGBColor)
            throws IllegalArgumentException {

        render(tileSpecs,
               targetImage,
               x,
               y,
               meshCellSize,
               scale,
               areaOffset,
               numberOfThreads,
               skipInterpolation,
               doFilter,
               false,
               false,
               imageProcessorCache,
               backgroundRGBColor,
               null,
               null);
    }

    /**
     * convert to 24bit RGB
     */
    static private ColorProcessor convertToRGB(
            final ImageProcessor tp,
            final TileSpec ts,
            final Double renderMinIntensity,
            final Double renderMaxIntensity) {

        // TODO: should render intensities only be applied if they constrain more than the tile spec (e.g. renderMin > tileMin) ?

        final double minIntensity = (renderMinIntensity == null) ? ts.getMinIntensity() : renderMinIntensity;
        final double maxIntensity = (renderMaxIntensity == null) ? ts.getMaxIntensity() : renderMaxIntensity;
        tp.setMinAndMax(minIntensity, maxIntensity);
        return tp.convertToColorProcessor();
    }


    static private BufferedImage targetToARGBImage(
            final ImageProcessorWithMasks target,
            final TileSpec ts,
            final boolean binaryMask,
            final Double renderMinIntensity,
            final Double renderMaxIntensity) {

        // convert to 24bit RGB
        final ColorProcessor cp = convertToRGB(target.ip, ts, renderMinIntensity, renderMaxIntensity);

        // set alpha channel
        final int[] cpPixels = (int[]) cp.getPixels();
        final byte[] alphaPixels;

        if (target.mask != null) {
            alphaPixels = (byte[]) target.mask.getPixels();
        } else {
            alphaPixels = (byte[]) target.outside.getPixels();
        }

        if (binaryMask) {
            for (int i = 0; i < cpPixels.length; ++i) {
                if (alphaPixels[i] == -1)
                    cpPixels[i] &= 0xffffffff;
                else
                    cpPixels[i] &= 0x00ffffff;
            }
        } else {
            for (int i = 0; i < cpPixels.length; ++i) {
                cpPixels[i] &= 0x00ffffff | (alphaPixels[i] << 24);
            }
        }

        final BufferedImage image = new BufferedImage(cp.getWidth(), cp.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final WritableRaster raster = image.getRaster();
        raster.setDataElements(0, 0, cp.getWidth(), cp.getHeight(), cpPixels);

        return image;
    }

    public static void render(final List<TileSpec> tileSpecs,
                              final BufferedImage targetImage,
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
                              final Double renderMaxIntensity)
            throws IllegalArgumentException {

        final int targetWidth = targetImage.getWidth();
        final int targetHeight = targetImage.getHeight();

        final double[] min = new double[ 2 ];
        final double[] max = new double[ 2 ];

        final Graphics2D targetGraphics = targetImage.createGraphics();

        if (backgroundRGBColor != null) {
            targetGraphics.setBackground(new Color(backgroundRGBColor));
            targetGraphics.clearRect(0, 0, targetWidth, targetHeight);
        }

        LOG.debug("render: entry, processing {} tile specifications, numberOfThreads={}",
                  tileSpecs.size(), numberOfThreads);

        final long tileLoopStart = System.currentTimeMillis();
        int tileSpecIndex = 0;
        long tileSpecStart;
        long loadMipStop;
        long filterStop;
        long loadMaskStop;
        long ctListCreationStop;
        long meshCreationStop;
        long sourceCreationStop;
        long targetCreationStop;
        long mapInterpolatedStop;
        long drawImageStop;

        for (final TileSpec ts : tileSpecs) {
            tileSpecStart = System.currentTimeMillis();

            final CoordinateTransformList<CoordinateTransform> ctl = createRenderTransform(ts, areaOffset, scale, x, y);

            Map.Entry<Integer, ImageAndMask> mipmapEntry;
            ImageAndMask imageAndMask = null;
            ImageProcessor widthAndHeightProcessor = null;
            int width = ts.getWidth();
            int height = ts.getHeight();
            boolean convertTo16Bit = (ts.getMaxIntensity()>255);
            // if width and height were not set, figure width and height
            if ((width < 0) || (height < 0)) {
                mipmapEntry = ts.getFirstMipmapEntry();
                imageAndMask = mipmapEntry.getValue();
                widthAndHeightProcessor = imageProcessorCache.get(imageAndMask.getImageUrl(), 0, false,convertTo16Bit);
                width = widthAndHeightProcessor.getWidth();
                height = widthAndHeightProcessor.getHeight();
            }

            // estimate average scale
            final double s = Utils.sampleAverageScale(ctl, width, height, meshCellSize);
            int mipmapLevel = Utils.bestMipmapLevel(s);

            int downSampleLevels = 0;
            final ImageProcessor ipMipmap;
            if (widthAndHeightProcessor == null) { // width and height were explicitly specified as parameters

                mipmapEntry = ts.getFloorMipmapEntry(mipmapLevel);
                imageAndMask = mipmapEntry.getValue();

                final int currentMipmapLevel = mipmapEntry.getKey();
                if (currentMipmapLevel < mipmapLevel) {
                    downSampleLevels = mipmapLevel - currentMipmapLevel;
                } else {
                    mipmapLevel = currentMipmapLevel;
                }

                ipMipmap = imageProcessorCache.get(imageAndMask.getImageUrl(), downSampleLevels, false,convertTo16Bit);

            } else if (mipmapLevel > 0) {

                downSampleLevels = mipmapLevel;
                ipMipmap = imageProcessorCache.get(imageAndMask.getImageUrl(), downSampleLevels, false,convertTo16Bit);

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
                maskSourceProcessor = imageProcessorCache.get(maskUrl, downSampleLevels, true,false);
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

            // get bounding box
            mesh.bounds(min, max);
            final int tx = (int)Math.max(0, Math.min(targetWidth, min[0]));
            final int ty = (int)Math.max(0, Math.min(targetHeight, min[1]));
            final int w = (int)(Math.min(targetWidth, max[0]) - tx) + 1;
            final int h = (int)(Math.min(targetHeight, max[1]) - ty) + 1;

            // skip if bounding box is not in target
            if (w <=0 || h <= 0) {
                LOG.debug("Skipping tile {} which is outside the FOV.", ts);
                continue;
            }

//            LOG.debug("Bounding box is {}x{}+{}+{} from min = {} and max = {}", w, h, tx, ty, min, max);

            mesh.translateTarget(-tx, -ty);

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

            // create a target
            final ImageProcessor tp = ipMipmap.createProcessor(w, h);

            final ImageProcessor maskTargetProcessor;
            if (maskSourceProcessor != null)
                maskTargetProcessor = new ByteProcessor(w, h);
            else
                maskTargetProcessor = null;

            final ImageProcessorWithMasks target = new ImageProcessorWithMasks(tp, maskTargetProcessor, null);

            targetCreationStop = System.currentTimeMillis();

            final RenderTransformMeshMappingWithMasks mapping = new RenderTransformMeshMappingWithMasks(mesh);

            final String mapType;
            if (skipInterpolation) {
                mapType = "";
                mapping.map(source, target, numberOfThreads);
            } else {
                mapType = " interpolated";
                mapping.mapInterpolated(source, target, numberOfThreads);
            }

            mapInterpolatedStop = System.currentTimeMillis();

            final BufferedImage image = targetToARGBImage(target, ts, binaryMask, renderMinIntensity, renderMaxIntensity);

            targetGraphics.drawImage(image, tx, ty, null);

            drawImageStop = System.currentTimeMillis();

            LOG.debug("render: tile {} took {} milliseconds to process (load mip:{}, downSampleLevels:{}, filter:{}, load mask:{}, ctList:{}, mesh:{}, source:{}, target:{}, map{}:{}, draw image:{}), cacheSize:{}",
                      tileSpecIndex,
                      drawImageStop - tileSpecStart,
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
                      drawImageStop - mapInterpolatedStop,
                      imageProcessorCache.size());

            tileSpecIndex++;
        }

        targetGraphics.dispose();

        LOG.debug("render: exit, {} tiles processed in {} milliseconds",
                  tileSpecs.size(),
                  System.currentTimeMillis() - tileLoopStart);
    }

    public static TileSpec deriveBoundingBox(
            final TileSpec tileSpec,
            final double meshCellSize,
            final boolean force) {

        if (! tileSpec.hasWidthAndHeightDefined()) {
            final Map.Entry<Integer, ImageAndMask> mipmapEntry = tileSpec.getFirstMipmapEntry();
            final ImageAndMask imageAndMask = mipmapEntry.getValue();
            final ImageProcessor imageProcessor = ImageProcessorCache.getNonCachedImage(imageAndMask.getImageUrl(), 0, false);
            tileSpec.setWidth((double) imageProcessor.getWidth());
            tileSpec.setHeight((double) imageProcessor.getHeight());
        }

        tileSpec.deriveBoundingBox(meshCellSize, force);

        return tileSpec;
    }

    public static BufferedImage renderWithNoise(final RenderParameters renderParameters,
                                                final boolean fillWithNoise) {

        LOG.info("renderWithNoise: entry, fillWithNoise={}", fillWithNoise);

        final BufferedImage bufferedImage = renderParameters.openTargetImage();
        final ByteProcessor ip = new ByteProcessor(bufferedImage.getWidth(), bufferedImage.getHeight());

        if (fillWithNoise) {
            mpicbg.ij.util.Util.fillWithNoise(ip);
            bufferedImage.getGraphics().drawImage(ip.createImage(), 0, 0, null);
        }

        Render.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        LOG.info("renderWithNoise: exit");

        return bufferedImage;
    }

    public static void main(final String[] args) {

        try {

            final long mainStart = System.currentTimeMillis();
            long parseStop = mainStart;
            long targetOpenStop = mainStart;
            long saveStart = mainStart;
            long saveStop = mainStart;

            final RenderParameters params = RenderParameters.parseCommandLineArgs(args);

            if (params.displayHelp()) {

                params.showUsage();

            } else {

                LOG.info("main: entry, params={}", params);

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

            LOG.debug("main: processing took {} milliseconds (parse command:{}, open target:{}, render tiles:{}, save target:{})",
                      saveStop - mainStart,
                      parseStop - mainStart,
                      targetOpenStop - parseStop,
                      saveStart - targetOpenStop,
                      saveStop - saveStart);

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
            System.exit(1);
        }

    }
}
