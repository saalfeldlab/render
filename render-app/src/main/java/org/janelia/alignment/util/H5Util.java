package org.janelia.alignment.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.loader.DynamicMaskLoader;
import org.janelia.alignment.loader.ImageLoader.LoaderType;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader;

/**
 * Utilities for working with HDF5 files.
 *
 * @author Eric Trautman
 */
public class H5Util {

    /**
     * @param  h5Reader              reader for HDF5 data set.
     * @param  h5Path                path of HDF5 file containing tile data.
     * @param  row                   tile row.
     * @param  column                tile column.
     * @param  z                     z coordinate of tile.
     * @param  maskWidth             width of masked area at left edge of tile (or null to leave left edge unmasked).
     * @param  maskHeight            height of masked area at top of tile (or null to leave top unmasked).
     * @param  referenceTransformId  id of scan correction transform used for all tiles (or null to skip correction).
     *
     * @return a {@link TileSpec} without setting the bounding box.
     *
     * @throws IllegalArgumentException
     *   if the h5 tile data cannot be read or is missing any required attributes.
     */
    public static TileSpec buildTileSpecWithoutBoundingBox(final N5HDF5Reader h5Reader,
                                                           final String h5Path,
                                                           final int row,
                                                           final int column,
                                                           final double z,
                                                           final Integer maskWidth,
                                                           final Integer maskHeight,
                                                           final String referenceTransformId)
            throws IllegalArgumentException {

        final String tileGroup = String.format("/0-%d-%d", row, column);

        final String tileIdPrefix;
        final String datFileNameKey = "dat_file_name";

        final String datFileName;
        try {
            datFileName = h5Reader.getAttribute(tileGroup, datFileNameKey, String.class);
        } catch (final NoSuchMethodError e) {
            throw new IllegalArgumentException(
                    "Failed to read " + datFileNameKey + " attribute from dataSet '" + tileGroup + "' of " + h5Path +
                    ".  If you are running within a Spark job, this error could occur because the default GSON " +
                    "library is very old (GSON 2.2.4) for many versions of Spark.  To fix this, add something like " +
                    "--conf spark.executor.extraClassPath=.../gson-2.10.1.jar to your launch command.", e);
        }

        final Matcher matcher = DAT_FILE_NAME_PATTERN.matcher(datFileName);
        if (matcher.matches() && matcher.groupCount() == 1) {
            tileIdPrefix = matcher.group(1);
        } else {
            throw new IllegalArgumentException("invalid dat_file_name '" + datFileName +
                                               "' for group '" + tileGroup + "' of " + h5Path);
        }
        final String tileId = tileIdPrefix + "." + z;

        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId(tileId);

        final Integer stageX = h5Reader.getAttribute(tileGroup, "FirstX", Integer.class);
        if (stageX == null) {
            throw new IllegalArgumentException("no FirstX defined for group '" + tileGroup + "' of " + h5Path);
        }

        final Integer stageY = h5Reader.getAttribute(tileGroup, "FirstY", Integer.class);
        if (stageY == null) {
            throw new IllegalArgumentException("no FirstY defined for group '" + tileGroup + "' of " + h5Path);
        }

        final Integer imageWidth = h5Reader.getAttribute(tileGroup, "XResolution", Integer.class);
        if (imageWidth == null) {
            throw new IllegalArgumentException("no XResolution defined for group '" + tileGroup + "' of " + h5Path);
        }

        final Integer imageHeight = h5Reader.getAttribute(tileGroup, "YResolution", Integer.class);
        if (imageHeight == null) {
            throw new IllegalArgumentException("no YResolution defined for group '" + tileGroup + "' of " + h5Path);
        }

        // make sure the mipmap.0 group and datasetAttributes exist, though we don't use them directly here
        final String mipmapZeroGroup = tileGroup + "/mipmap.0";
        final DatasetAttributes datasetAttributes = h5Reader.getDatasetAttributes(mipmapZeroGroup);
        if (datasetAttributes == null) {
            throw new IllegalArgumentException("no datasetAttributes defined for group '" + mipmapZeroGroup +
                                               "' of " + h5Path);
        }

        // TODO: figure out how to include/derive distanceZ if that ever becomes relevant
        //       - python code calculates distanceZ from working distances (WD) of current and prior layer tiles:
        //         distance_z = (working_distance - prior_working_distance) * 1000000

        final LayoutData layoutData = new LayoutData(String.valueOf(z),
                                                     null,
                                                     null,
                                                     row,
                                                     column,
                                                     stageX.doubleValue(),
                                                     stageY.doubleValue(),
                                                     null);
        tileSpec.setLayout(layoutData);
        tileSpec.setZ(z);
        tileSpec.setWidth(imageWidth.doubleValue());
        tileSpec.setHeight(imageHeight.doubleValue());

        // all HDF5 source data is assumed to be 8-bit unsigned data
        tileSpec.setMinAndMaxIntensity(0.0, 255.0, null);

        final ChannelSpec channelSpec = new ChannelSpec();

        // file:///nrs/cellmap/data/jrc_mus-pancreas-5/align/Merlin-6262/2023/10/06/19/Merlin-6262_23-10-06_195119.uint8.h5
        //   ?dataSet=/0-0-0/mipmap.0&z=0
        final String imageUrl = "file://" + h5Path + "?dataSet=" + mipmapZeroGroup + "&z=0";

        String maskUrl = null;
        LoaderType maskLoaderType = null;
        if ((maskWidth != null) || (maskHeight != null)) {
            final int minX = maskWidth == null ? 0 : maskWidth;
            final int minY = maskHeight == null ? 0 : maskHeight;
            final DynamicMaskLoader.DynamicMaskDescription maskDescription =
                    new DynamicMaskLoader.DynamicMaskDescription(minX, minY,
                                                                 imageWidth, imageHeight,
                                                                 imageWidth, imageHeight);

            // mask://outside-box?minX=100&minY=30&maxX=8625&maxY=8250&width=8625&height=8250
            maskUrl = maskDescription.toString();
            maskLoaderType = LoaderType.DYNAMIC_MASK;
        }

        final ImageAndMask imageAndMask = new ImageAndMask(imageUrl, LoaderType.H5_SLICE, null,
                                                           maskUrl, maskLoaderType, null);
        channelSpec.putMipmap(0, imageAndMask);
        tileSpec.addChannel(channelSpec);

        final ListTransformSpec transformList = new ListTransformSpec();
        if (referenceTransformId != null) {
            transformList.addSpec(new ReferenceTransformSpec(referenceTransformId));
        }
        transformList.addSpec(new LeafTransformSpec("mpicbg.trakem2.transform.AffineModel2D",
                                                    "1 0 0 1 " + stageX + " " + stageY));
        tileSpec.setTransforms(transformList);

        // simplify the channel spec by converting it to legacy form
        tileSpec.convertSingleChannelSpecToLegacyForm();

        return tileSpec;
    }

    // Merlin-6262_23-10-06_195119_0-0-0.dat
    private static final Pattern DAT_FILE_NAME_PATTERN =
            Pattern.compile("^.*_(\\d{2}-\\d{2}-\\d{2}_\\d{6}_\\d-\\d-\\d)\\.dat$");
}
