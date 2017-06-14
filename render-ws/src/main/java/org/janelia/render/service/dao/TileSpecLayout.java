package org.janelia.render.service.dao;

import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.bson.Document;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;

/**
 * This class contains a set of named instances for formatting tile spec "layout" data.
 *
 * @author Eric Trautman
 */
public class TileSpecLayout {

    /**
     * Common interface for all formats.
     */
    public interface Formatter {
        String formatHeader(final StackMetaData stackMetaData);
        String formatTileSpec(final TileSpec tileSpec,
                              final String stackRequestUri);
        Document getOrderBy();
    }

    /**
     * Set of supported format instances.
     */
    public enum Format implements Formatter {

        KARSH(
                new Formatter() {

                    @Override
                    public String formatHeader(final StackMetaData stackMetaData) {
                        return null;
                    }

                    @Override
                    public String formatTileSpec(final TileSpec tileSpec,
                                                 final String stackRequestUri) {

                        String affineData = getAffineData(tileSpec, '\t');

                        String sectionId = null;
                        Integer imageCol = null;
                        Integer imageRow = null;
                        String camera = null;
                        String temca = null;
                        Double rotation = null;

                        final LayoutData layout = tileSpec.getLayout();
                        if (layout != null) {
                            sectionId = layout.getSectionId();
                            imageCol = layout.getImageCol();
                            imageRow = layout.getImageRow();
                            camera = layout.getCamera();
                            temca = layout.getTemca();
                            rotation = layout.getRotation();
                            if (affineData == null) {
                                affineData = "1.0\t0.0\t" + layout.getStageX() + "\t0.0\t1.0\t" + layout.getStageY();
                            }
                        }

                        final String rawPath = getRawPath(tileSpec);

                        // {stackRequestUri}/tile/{tileId}/render-parameters
                        final String uriString = stackRequestUri + "/tile/" + tileSpec.getTileId() + "/render-parameters";

                        // sectionId, 1.0, 0.0, stageX, 0.0, 1.0, stageY, imageCol, imageRow, camera, rawPath, temca, rotation, z
                        return sectionId + '\t' + tileSpec.getTileId() + '\t' + affineData + '\t' +
                               imageCol + '\t' + imageRow + '\t' + camera + '\t' + rawPath + '\t' +
                               temca + '\t' + rotation + '\t' + tileSpec.getZ() + '\t' + uriString + '\n';
                    }

                    @Override
                    public Document getOrderBy() {
                        // Khaled's code needs transform data to be sorted in this way
                        // to optimize the matrix produced from the data.
                        return new Document("z", 1).append("minY", 1).append("minX", 1);
                    }
                }),

        SCHEFFER(
                new Formatter() {

                    @Override
                    public String formatHeader(final StackMetaData stackMetaData) {
                        String header = "BBOX ";
                        final StackStats stats = stackMetaData.getStats();
                        if (stats != null) {
                            final Bounds bounds = stats.getStackBounds();
                            if (bounds != null) {
                                header = header +
                                         bounds.getMinX() + ' ' + bounds.getMinY() + ' ' +
                                         bounds.getMaxX() + ' ' + bounds.getMaxY();
                            }
                        }
                        return header + '\n';
                    }

                    @Override
                    public String formatTileSpec(final TileSpec tileSpec,
                                                 final String stackRequestUri) {

                        // TRANSFORM lines have the image name, an affine transformation, and the image size.
                        //
                        // The line is TRANSFORM <name> A B C D E F <source-image-width> <source-image-height>.
                        //
                        // The transformation matrix is
                        // |A B C | |X|   |x’|
                        // |D E F | |Y| = |y’|
                        // |1|
                        //
                        // Where X and Y are the pixel locations in the image, and x’ and y’ the location in the final global space.

                        final String affineData = getAffineData(tileSpec, ' ');
                        final String rawPath = getRawPath(tileSpec);

                        return "TRANSFORM '" + rawPath + "' " + affineData + ' ' +
                               tileSpec.getWidth() + ' ' + tileSpec.getHeight() + '\n';
                    }

                    @Override
                    public Document getOrderBy() {
                        // Lou's code needs transform data sorted in scope acquisition order
                        // which is encoded in the tileIds (e.g. 235328_0-0-0, 235328_0-0-1, ...).
                        return new Document("z", 1).append("tileId", 1);
                    }
                });

        private final Formatter formatter;

        Format(final Formatter formatter) {
            this.formatter = formatter;
        }

        @Override
        public String formatHeader(final StackMetaData stackMetaData) {
            return formatter.formatHeader(stackMetaData);
        }

        @Override
        public String formatTileSpec(final TileSpec tileSpec,
                                     final String stackRequestUri) {
            return formatter.formatTileSpec(tileSpec, stackRequestUri);
        }

        @Override
        public Document getOrderBy() {
            return formatter.getOrderBy();
        }

    }

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    @Nullable
    private static String getAffineData(final TileSpec tileSpec,
                                        final char delimiter) {

        String affineData = null;

        if (tileSpec.hasTransforms()) {
            final TransformSpec lastSpec = tileSpec.getLastTransform();
            if (lastSpec instanceof LeafTransformSpec) {
                final LeafTransformSpec leafSpec = (LeafTransformSpec) lastSpec;
                final String[] data = WHITESPACE_PATTERN.split(leafSpec.getDataString(), -1);
                if (data.length == 6) {

                    // Need to translate affine matrix order "back" for Karsh aligner.
                    //
                    // Saalfeld order: (0) scale-x (1) shear-y (2) shear-x (3) scale-y (4) translate-x (5) translate-y
                    // Karsh order is: (0) scale-x (2) shear-x (4) translate-x (1) shear-y (3) scale-y (5) translate-y

                    affineData = data[0] + delimiter + data[2] + delimiter + data[4] + delimiter +
                                 data[1] + delimiter + data[3] + delimiter + data[5];

                } else if (data.length > 6) {

                    // hack to export polynomial data in layout format (keep in Saalfeld order)

                    final int lengthMinusOne = data.length - 1;
                    final StringBuilder sb = new StringBuilder(1024);

                    sb.append(data.length);
                    sb.append(delimiter);

                    for (int i = 0; i < lengthMinusOne; i++) {
                        sb.append(data[i]);
                        sb.append(delimiter);
                    }
                    sb.append(data[lengthMinusOne]);

                    affineData = sb.toString();
                }

            }
        }

        return affineData;

    }

    @Nullable
    private static String getRawPath(final TileSpec tileSpec) {
        String rawPath = null;
        final Map.Entry<Integer, ImageAndMask> firstMipmapEntry = tileSpec.getFirstMipmapEntry();
        if (firstMipmapEntry != null) {
            final ImageAndMask imageAndMask = firstMipmapEntry.getValue();
            rawPath = imageAndMask.getImageFilePath();
        }
        return rawPath;
    }

}
