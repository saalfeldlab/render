package org.janelia.alignment.loader;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mpicbg.trakem2.util.Downsampler;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileSpec;

import static org.janelia.alignment.loader.DynamicMaskLoader.LEVEL_PATTERN;
import static org.janelia.alignment.spec.stack.MipmapPathBuilder.DYNAMIC_MASK_PROTOCOL;

/**
 * Loads a dynamically created mask whose attributes are specified by a URI that
 * identifies bounding boxes for multiple tiles.
 * The mask identifies all areas outside the specified boxes with black pixels (0)
 * and all areas inside the boxes with white pixels (255).
 */
public class MultiBoxDynamicMaskLoader
        implements ImageLoader {

    /** Shareable instance of this loader. */
    public static final MultiBoxDynamicMaskLoader INSTANCE = new MultiBoxDynamicMaskLoader();

    // mask://outside-boxes?width=20781&height=17963&count=91&boxes=minX,minY,width,height,minX,minY,width,height,...
    private static final Pattern OUTSIDE_BOXES_URL_PATTERN =
            Pattern.compile(DYNAMIC_MASK_PROTOCOL +
                            "outside-boxes\\?width=(\\d+)&height=(\\d+)&count=(\\d+)&boxes=(((\\d+),)*(\\d+)).*");

    @Override
    public boolean hasSame3DContext(final ImageLoader otherLoader) {
        return true;
    }

    @Override
    public ImageProcessor load(final String urlString)
            throws IllegalArgumentException {

        ByteProcessor maskProcessor;

        final MultiBoxDynamicMaskDescription description = parseUrl(urlString);

        maskProcessor = new ByteProcessor(description.width, description.height);

        maskProcessor.setColor(255);
        for (final Rectangle box : description.boxList) {
            maskProcessor.fillRect(box.x, box.y, box.width, box.height);
        }

        final Matcher levelMatcher = LEVEL_PATTERN.matcher(urlString);
        if (levelMatcher.matches() && levelMatcher.groupCount() == 1) {
            final int desiredLevel = Integer.parseInt(levelMatcher.group(1));
            if (desiredLevel > 0) {
                maskProcessor = Downsampler.downsampleByteProcessor(maskProcessor, desiredLevel);
            }
        }

        return maskProcessor;
    }

    public static class MultiBoxDynamicMaskDescription {
        public final int width;
        public final int height;
        public final List<Rectangle> boxList;

        public MultiBoxDynamicMaskDescription(final int width,
                                              final int height,
                                              final List<Rectangle> boxList) {
            this.width = width;
            this.height = height;
            this.boxList = boxList;
        }

        public MultiBoxDynamicMaskDescription(final List<Bounds> fullScaleBoundsList,
                                              final double renderScale,
                                              final double scaledWidth,
                                              final double scaledHeight)
                throws IllegalArgumentException {

            if ((fullScaleBoundsList == null) || fullScaleBoundsList.isEmpty()) {
                throw new IllegalArgumentException("boundsList must not be null or empty");
            }

            final Bounds fullScaleAggregateBounds = Bounds.unionAll(fullScaleBoundsList);
            final Bounds scaledAggregateBounds =
                    new Bounds(0.0,
                               0.0,
                               scaledWidth,
                               scaledHeight);

            this.width = scaledAggregateBounds.getWidth();
            this.height = scaledAggregateBounds.getHeight();

            this.boxList = new ArrayList<>();
            for (final Bounds fullScaleBound : fullScaleBoundsList) {
                final double fullScaleX = fullScaleBound.getMinX() - fullScaleAggregateBounds.getMinX();
                final double fullScaleY = fullScaleBound.getMinY() - fullScaleAggregateBounds.getMinY();
                final int scaledX = (int) Math.floor(fullScaleX * renderScale);
                final int scaledY = (int) Math.floor(fullScaleY * renderScale);

                // note that width and height need to be floored (not ceiled) to be in sync with the image
                final int scaledBoundWidth = (int) Math.floor(fullScaleBound.getDeltaX() * renderScale);
                final int scaledBoundHeight = (int) Math.floor(fullScaleBound.getDeltaY() * renderScale);

                this.boxList.add(new Rectangle(scaledX, scaledY, scaledBoundWidth, scaledBoundHeight));
            }

        }

        @Override
        public String toString() {

            final StringBuilder boxesStringBuilder = new StringBuilder();
            for (final Rectangle box : boxList) {
                if (boxesStringBuilder.length() > 0) {
                    boxesStringBuilder.append(",");
                }
                boxesStringBuilder.append(box.x).append(",").append(box.y)
                                  .append(",").append(box.width).append(",").append(box.height);
            }

            return "mask://outside-boxes?width=" + width + "&height=" + height + "&count=" + boxList.size() +
                   "&boxes=" + boxesStringBuilder;
        }

    }

    public static MultiBoxDynamicMaskDescription parseUrl(final String urlString)
            throws IllegalArgumentException {

        final Matcher urlMatcher = OUTSIDE_BOXES_URL_PATTERN.matcher(urlString);

        if (! urlMatcher.matches()) {
            throw new IllegalArgumentException("failed to parse dynamic mask URL: " + urlString);
        }

        final int width = Integer.parseInt(urlMatcher.group(1));
        final int height = Integer.parseInt(urlMatcher.group(2));
        final int boxCount = Integer.parseInt(urlMatcher.group(3));
        final String boxesValueString = urlMatcher.group(4);

        final int expectedBoxValueCount = boxCount * 4;
        final String[] boxValues = boxesValueString.split(",");
        if (boxValues.length != expectedBoxValueCount) {
            throw new IllegalArgumentException("expected " + expectedBoxValueCount + " box values but found " +
                                               boxValues.length + " in dynamic mask URL: " + urlString);
        }

        final List<Rectangle> boxes = new ArrayList<>(boxCount);
        for (int i = 0; i < boxCount; i++) {
            final int offset = i * 4;
            final int minX = Integer.parseInt(boxValues[offset]);
            final int minY = Integer.parseInt(boxValues[offset + 1]);
            final int boxWidth = Integer.parseInt(boxValues[offset + 2]);
            final int boxHeight = Integer.parseInt(boxValues[offset + 3]);
            boxes.add(new Rectangle(minX, minY, boxWidth, boxHeight));
        }

        return new MultiBoxDynamicMaskDescription(width, height, boxes);
    }

    public static MultiBoxDynamicMaskDescription buildMaskDescription(final RenderParameters renderParameters,
                                                                      final double renderScale,
                                                                      final int imageWidth,
                                                                      final int imageHeight)
            throws IllegalStateException {

        final List<Bounds> fullScaleBoundsList = new ArrayList<>();
        for (final TileSpec tileSpec : renderParameters.getTileSpecs()) {
            if (tileSpec.getFirstMipmapEntry().getValue().hasMask()) {
                throw new IllegalStateException("tile " + tileSpec.getTileId() +
                                                " has a mask which is not supported for LayerMFOVImage rendering");
            }
            fullScaleBoundsList.add(tileSpec.toTileBounds());
        }

        return new MultiBoxDynamicMaskDescription(fullScaleBoundsList,
                                                  renderScale,
                                                  imageWidth,
                                                  imageHeight);
    }
}
