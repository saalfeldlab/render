package org.janelia.alignment.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Cursor;
import net.imglib2.KDTree;
import net.imglib2.RandomAccessible;
import net.imglib2.RealCursor;
import net.imglib2.RealPoint;
import net.imglib2.RealPointSampleList;
import net.imglib2.RealRandomAccess;
import net.imglib2.Sampler;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.neighborsearch.NearestNeighborSearch;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.composite.RealComposite;

/**
 * Utility for building an {@link AffineWarpField} of arbitrary (specified) resolution from consensus match point sets.
 *
 * The {@link #build} method uses Stephan Saalfeld's
 * <a href="https://github.com/axtimwalde/experiments/blob/master/src/main/java/mpicbg/ij/plugin/SIFT_ExtractMultiplePointRois.java#L174-L188">
 *     nearest neighbor logic
 * </a>
 * to assign affine parameters to warp field cells.
 *
 * The {@link #toIndexGridString} method provides an "ASCII Voronoi diagram" of the mapped consensus sets.
 *
 * @author Eric Trautman
 */
public class ConsensusWarpFieldBuilder {

    public enum BuildMethod { SIMPLE, INTERPOLATE }

    private final double width;
    private final double height;
    private final int rowCount;
    private final int columnCount;

    private final double pixelsPerRow;
    private final double pixelsPerColumn;
    private final List<Affine2D> consensusSetModelList;
    private final RealPointSampleList<ARGBType> consensusSetIndexSamples;

    /**
     * Sets up a field with the specified dimensions.
     *
     * @param  width                pixel width of the warp field.
     * @param  height               pixel height of the warp field.
     * @param  rowCount             number of affine rows in the warp field.
     * @param  columnCount          number of affine columns in the warp field.
     */
    public ConsensusWarpFieldBuilder(final double width,
                                     final double height,
                                     final int rowCount,
                                     final int columnCount) {
        this.width = width;
        this.height = height;
        this.rowCount = rowCount;
        this.columnCount = columnCount;

        this.pixelsPerRow = height / rowCount;
        this.pixelsPerColumn = width / columnCount;
        this.consensusSetModelList = new ArrayList<>();
        this.consensusSetIndexSamples = new RealPointSampleList<>(2);
    }

    /**
     * @return number of cells in the warp field grid.
     */
    public int getNumberOfCells() {
        return rowCount * columnCount;
    }

    /**
     * @return number of consensus sets remaining in the grid after nearest neighbor analysis is completed.
     */
    public int getNumberOfConsensusSetsInGrid() {
        return getNumberOfConsensusSets(buildModelIndexGrid());
    }

    /**
     * Adds the specified consensus set data to this builder so that it can be
     * used by the {@link #build} method later.
     *
     * @param  alignmentModel  alignment model for this consensus point set.
     * @param  points          set of points associated with the model.
     */
    public void addConsensusSetData(final Affine2D alignmentModel,
                                    final List<RealPoint> points) {

        final ARGBType consensusSetIndex = new ARGBType(consensusSetModelList.size());
        consensusSetModelList.add(alignmentModel);

        double x;
        double y;
        for (final RealPoint point : points) {
            x = point.getDoublePosition(0) / pixelsPerColumn;
            y = point.getDoublePosition(1) / pixelsPerRow;
            consensusSetIndexSamples.add(new RealPoint(x, y), consensusSetIndex);
        }

    }

    /**
     * @return a warp field built from this builder's consensus set data.
     *         The returned field will utilize the default interpolator factory.
     */
    public AffineWarpField build() {
        return build(AffineWarpField.getDefaultInterpolatorFactory());
    }

    /**
     * @param  interpolatorFactory  factory to include in the returned warp field.
     *
     * @return a warp field built from this builder's consensus set data.
     *         The returned field will utilize the specified interpolator factory.
     */
    public AffineWarpField build(final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> interpolatorFactory) {

        final int[] modelIndexGrid = buildModelIndexGrid();

        final AffineWarpField affineWarpField =
                new AffineWarpField(width, height, rowCount, columnCount, interpolatorFactory);

        for (int row = 0; row < rowCount; row++) {
            for (int column = 0; column < columnCount; column++) {

                final int gridIndex = (row * rowCount) + column;
                final int modelIndex = modelIndexGrid[gridIndex];
                final Affine2D model = consensusSetModelList.get(modelIndex);

                final double[] affineMatrixElements = new double[6];
                model.toArray(affineMatrixElements);

                affineWarpField.set(row, column, affineMatrixElements);
            }
        }

        return affineWarpField;
    }

    /**
     * @return ASCII Voronoi diagram of the consensus set indexes for each warp field cell.
     */
    public String toIndexGridString() {

        final int numberOfCells = getNumberOfCells();
        final int[] modelIndexGrid = buildModelIndexGrid();

        final int indexPlusSpaceWidth = String.valueOf(consensusSetModelList.size() - 1).length() + 1;
        final String format = "%" + indexPlusSpaceWidth + "d";
        final StringBuilder sb = new StringBuilder();

        sb.append(rowCount).append('x').append(columnCount).append(" grid with ");
        sb.append(getNumberOfConsensusSets(modelIndexGrid)).append(" distinct sets:\n");

        for (int i = 0; i < numberOfCells; i++) {
            if (i % columnCount == 0) {
                sb.append('\n');
            }
            sb.append(String.format(format, modelIndexGrid[i]));
        }

        return sb.toString();
    }

    public ConsensusWarpFieldBuilder mergeBuilders(final ConsensusWarpFieldBuilder otherBuilder) {

        validateConsistency("rowCount", rowCount, otherBuilder.rowCount);
        validateConsistency("columnCount", columnCount, otherBuilder.columnCount);
        validateConsistency("width", width, otherBuilder.width);
        validateConsistency("height", height, otherBuilder.height);

        final Map<Integer, List<RealPoint>> cellToPointsMap = new LinkedHashMap<>();
        mapCellsToPoints(cellToPointsMap, consensusSetIndexSamples.cursor());
        mapCellsToPoints(cellToPointsMap, otherBuilder.consensusSetIndexSamples.cursor());

        final int[] modelIndexGrid = buildModelIndexGrid();
        final int[] otherModelIndexGrid = otherBuilder.buildModelIndexGrid();

        LOG.info("mergeBuilder: mapped points to {} cells", cellToPointsMap.size());

        final Map<String, List<RealPoint>> setPairToPointsMap = new LinkedHashMap<>();
        List<RealPoint> pointList;
        for (int i = 0; i < modelIndexGrid.length; i++) {
            final String setPair = modelIndexGrid[i] + "::" + otherModelIndexGrid[i];
            pointList = setPairToPointsMap.get(setPair);
            if (pointList == null) {
                pointList = new ArrayList<>();
                setPairToPointsMap.put(setPair, pointList);
            }
            final List<RealPoint> cellPoints = cellToPointsMap.get(i);
            if (cellPoints != null) {
                pointList.addAll(cellPoints);
            }
        }

        LOG.info("mergeBuilder: merged result contains {} consensus sets", setPairToPointsMap.size());

        final ConsensusWarpFieldBuilder mergedBuilder =
                new ConsensusWarpFieldBuilder(width, height, rowCount, columnCount);

        for (final List<RealPoint> setPoints : setPairToPointsMap.values()) {
            mergedBuilder.addConsensusSetData(new AffineModel2D(), setPoints);
        }

        return mergedBuilder;
    }

    public static double[] getAffineMatrixElements(final RealRandomAccess<RealComposite<DoubleType>> warpFieldAccessor,
                                                   final double[] location) {
        warpFieldAccessor.setPosition(location);
        final RealComposite<DoubleType> coefficients = warpFieldAccessor.get();
        return new double[] {
                coefficients.get(0).getRealDouble(),
                coefficients.get(1).getRealDouble(),
                coefficients.get(2).getRealDouble(),
                coefficients.get(3).getRealDouble(),
                coefficients.get(4).getRealDouble(),
                coefficients.get(5).getRealDouble()
        };
    }

    public static LeafTransformSpec buildSimpleWarpFieldTransformSpec(final AffineWarpField warpField,
                                                                      final Map<HierarchicalStack, AffineWarpField> tierStackToConsensusFieldMap,
                                                                      final double[] locationOffsets,
                                                                      final String warpFieldTransformId) {
        AffineWarpField effectiveWarpField = warpField;

        if ((warpField != null) && (tierStackToConsensusFieldMap.size() > 0)) {

            LOG.info("buildSimpleWarpFieldTransformSpec: creating high resolution warp field to accommodate consensus set data for {} region(s)",
                     tierStackToConsensusFieldMap.size());

            final AffineWarpField firstConsensusField = tierStackToConsensusFieldMap.values().iterator().next();
            final int consensusRowCount = firstConsensusField.getRowCount();
            final int consensusColumnCount = firstConsensusField.getColumnCount();

            final AffineWarpField hiResField = warpField.getHighResolutionCopy(consensusRowCount,
                                                                               consensusColumnCount);
            for (final HierarchicalStack tierStack : tierStackToConsensusFieldMap.keySet()) {
                final AffineWarpField consensusField = tierStackToConsensusFieldMap.get(tierStack);
                final int startHiResRow = tierStack.getTierRow() * consensusRowCount;
                final int startHiResColumn = tierStack.getTierColumn() * consensusColumnCount;
                for (int row = 0; row < consensusRowCount; row++) {
                    for (int column = 0; column < consensusColumnCount; column++) {
                        hiResField.set((startHiResRow + row),
                                       (startHiResColumn + column),
                                       consensusField.get(row, column));
                    }
                }
            }

            effectiveWarpField = hiResField;
        }

        final AffineWarpFieldTransform warpFieldTransform =
                new AffineWarpFieldTransform(locationOffsets, effectiveWarpField);

        return new LeafTransformSpec(warpFieldTransformId,
                                     null,
                                     AffineWarpFieldTransform.class.getName(),
                                     warpFieldTransform.toDataString());
    }

    public static LeafTransformSpec buildInterpolatedWarpFieldTransformSpec(final AffineWarpField warpField,
                                                                            final Map<HierarchicalStack, AffineWarpField> tierStackToConsensusFieldMap,
                                                                            final double[] locationOffsets,
                                                                            final String warpFieldTransformId) {
        AffineWarpField effectiveWarpField = warpField;

        if ((warpField != null) && (tierStackToConsensusFieldMap.size() > 0)) {

            LOG.info("buildInterpolatedWarpFieldTransformSpec: creating high resolution warp field to accommodate consensus set data for {} region(s)",
                     tierStackToConsensusFieldMap.size());

            effectiveWarpField = warpField.getCopy(); // make copy since we need to modify the field ...

            final AffineWarpField firstConsensusField = tierStackToConsensusFieldMap.values().iterator().next();
            final int consensusRowCount = firstConsensusField.getRowCount();
            final int consensusColumnCount = firstConsensusField.getColumnCount();

            final Map<Integer, AffineWarpField> gridIndexToConsensusFieldMap = new HashMap<>();
            for (final HierarchicalStack tierStack : tierStackToConsensusFieldMap.keySet()) {
                final AffineWarpField consensusField = tierStackToConsensusFieldMap.get(tierStack);

                final RealRandomAccess<RealComposite<DoubleType>> consensusFieldAccessor = consensusField.getAccessor();
                final double[] consensusFieldCenter = { (consensusField.getWidth() / 2.0), (consensusField.getHeight() / 2.0) };

                effectiveWarpField.set(tierStack.getTierRow(),
                                       tierStack.getTierColumn(),
                                       getAffineMatrixElements(consensusFieldAccessor, consensusFieldCenter));

                final int gridIndex = (tierStack.getTierRow() * tierStack.getTotalTierRowCount()) +
                                      tierStack.getTierColumn();
                gridIndexToConsensusFieldMap.put(gridIndex, consensusField);
            }

            final RealRandomAccess<RealComposite<DoubleType>> warpFieldAccessor = effectiveWarpField.getAccessor();

            final int hiResRowCount = effectiveWarpField.getRowCount() * consensusRowCount;
            final int hiResColumnCount = effectiveWarpField.getColumnCount() * consensusColumnCount;

            final AffineWarpField hiResField = new AffineWarpField(effectiveWarpField.getWidth(),
                                                                   effectiveWarpField.getHeight(),
                                                                   hiResRowCount,
                                                                   hiResColumnCount,
                                                                   effectiveWarpField.getInterpolatorFactory());

            final double hiResRowHeight = hiResField.getHeight() / hiResRowCount;
            final double halfHiResRowHeight = hiResRowHeight / 2.0;
            final double hiResColumnWidth = hiResField.getWidth() / hiResColumnCount;
            final double halfHiResColumnWidth = hiResColumnWidth / 2.0;

            final int warpFieldRowCount = effectiveWarpField.getRowCount();
            final int warpFieldColumnCount = effectiveWarpField.getColumnCount();

            for (int row = 0; row < warpFieldRowCount; row++) {
                for (int column = 0; column < warpFieldColumnCount; column++) {

                    final int gridIndex = (row * warpFieldRowCount) + column;
                    final AffineWarpField consensusField = gridIndexToConsensusFieldMap.get(gridIndex);

                    for (int consensusRow = 0; consensusRow < consensusRowCount; consensusRow++) {
                        for (int consensusColumn = 0; consensusColumn < consensusColumnCount; consensusColumn++) {

                            final int hiResRow = (row * consensusRowCount) + consensusRow;
                            final int hiResColumn = (column * consensusColumnCount) + consensusColumn;

                            if (consensusField == null) {
                                final double centerX = (hiResColumn * hiResColumnWidth) + halfHiResColumnWidth;
                                final double centerY = (hiResRow * hiResRowHeight) + halfHiResRowHeight;
                                final double[] center = {centerX, centerY};
                                hiResField.set(hiResRow, hiResColumn, getAffineMatrixElements(warpFieldAccessor, center));
                            } else {
                                hiResField.set(hiResRow, hiResColumn, consensusField.get(consensusRow, consensusColumn));
                            }

                        }
                    }
                }
            }

            effectiveWarpField = hiResField;
        }

        final AffineWarpFieldTransform warpFieldTransform =
                new AffineWarpFieldTransform(locationOffsets, effectiveWarpField);

        return new LeafTransformSpec(warpFieldTransformId,
                                     null,
                                     AffineWarpFieldTransform.class.getName(),
                                     warpFieldTransform.toDataString());
    }

    private void mapCellsToPoints(final Map<Integer, List<RealPoint>> cellToPointsMap,
                                  final RealCursor<ARGBType> cursor) {

        List<RealPoint> pointList;

        while (cursor.hasNext()) {

            cursor.fwd();

            final double x = cursor.getDoublePosition(0) * pixelsPerColumn;
            final double y = cursor.getDoublePosition(1) * pixelsPerRow;
            final int row = (int) ((y / height) * rowCount);
            final int column = (int) ((x / width) * columnCount);
            final int gridIndex = (row * rowCount) + column;

            pointList = cellToPointsMap.get(gridIndex);
            if (pointList == null) {
                pointList = new ArrayList<>();
                cellToPointsMap.put(gridIndex, pointList);
            }

            pointList.add(new RealPoint(x, y));
        }
    }

    private void validateConsistency(final String context,
                                     final Object expectedValue,
                                     final Object actualValue)
            throws IllegalArgumentException {
        if (! expectedValue.equals(actualValue)) {
            throw new IllegalArgumentException(
                    context + " is inconsistent, expected " + expectedValue + " but was " + actualValue);
        }
    }

    private int[] buildModelIndexGrid() {

        final int[] targetCellIndexes = new int[getNumberOfCells()];

        final ArrayImg<ARGBType, IntArray> target = ArrayImgs.argbs(targetCellIndexes, columnCount, rowCount);
        final KDTree<ARGBType> kdTree = new KDTree<>(consensusSetIndexSamples);
        final NearestNeighborSearch<ARGBType> nnSearchSamples = new NearestNeighborSearchOnKDTree<>(kdTree);

        final Cursor<ARGBType> targetCursor = target.localizingCursor();

        Sampler<ARGBType> sampler;
        ARGBType sampleItem;
        ARGBType targetItem;
        while (targetCursor.hasNext()) {

            targetCursor.fwd();
            nnSearchSamples.search(targetCursor);
            sampler = nnSearchSamples.getSampler();

            sampleItem = sampler.get();
            targetItem = targetCursor.get();
            targetItem.set(sampleItem);
        }

        return targetCellIndexes;
    }

    private int getNumberOfConsensusSets(final int[] modelIndexGrid) {
        final Set<Integer> distinctModelIndexes = new HashSet<>();
        for (final int modelIndex : modelIndexGrid) {
            distinctModelIndexes.add(modelIndex);
        }
        return distinctModelIndexes.size();

    }

    private static final Logger LOG = LoggerFactory.getLogger(ConsensusWarpFieldBuilder.class);

}
