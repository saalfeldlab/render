package org.janelia.render.client.spark.n5;

import ij.process.ShortProcessor;

import java.util.List;

import net.imglib2.util.Intervals;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.ShortBoxRenderer;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.util.Grid;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.ImageProcessorCacheSpec;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.zspacing.ThicknessCorrectionData;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 * Export a 16-bit render stack to N5.
 */
public class ShortN5Client
        extends N5Client {

    private static final Logger LOG = LoggerFactory.getLogger(ShortN5Client.class);

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {

                final N5Client.Parameters parameters = new N5Client.Parameters();
                parameters.parse(args);
                parameters.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final ShortN5Client client = new ShortN5Client(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    public ShortN5Client(final N5Client.Parameters parameters) {
        super(parameters);
    }

    @Override
    public DataType getDataType() {
        return DataType.UINT16;
    }

    @Override
    public void renderStack(final JavaSparkContext sparkContext,
                            final int[] blockSize,
                            final String fullScaleDatasetName,
                            final ThicknessCorrectionData thicknessCorrectionData,
                            final Bounds boundsForRun,
                            final long[] min,
                            final long[] dimensions,
                            final boolean is2DVolume,
                            final ImageProcessorCacheSpec cacheSpec,
                            final Long minZToRender) throws IllegalArgumentException {

        final N5Client.Parameters parameters = getParameters();

        final ShortBoxRenderer boxRenderer = new ShortBoxRenderer(parameters.renderWeb.baseDataUrl,
                                                                  parameters.renderWeb.owner,
                                                                  parameters.renderWeb.project,
                                                                  parameters.stack,
                                                                  parameters.tileWidth,
                                                                  parameters.tileHeight,
                                                                  1.0,
                                                                  parameters.minIntensity,
                                                                  parameters.maxIntensity,
                                                                  parameters.exportMask);

        if (is2DVolume) {
            throw new IllegalArgumentException("2D export for 16-bit stacks is not implemented");
        } else {
            saveRenderStack(
                    sparkContext,
                    boxRenderer,
                    parameters.tileWidth,
                    parameters.tileHeight,
                    parameters.n5Path,
                    fullScaleDatasetName,
                    min,
                    dimensions,
                    blockSize,
                    thicknessCorrectionData,
                    cacheSpec,
                    minZToRender);
        }
    }

    private static void saveRenderStack(final JavaSparkContext sc,
                                        final ShortBoxRenderer boxRenderer,
                                        final int tileWidth,
                                        final int tileHeight,
                                        final String n5Path,
                                        final String datasetName,
                                        final long[] min,
                                        final long[] dimensions,
                                        final int[] blockSize,
                                        final ThicknessCorrectionData thicknessCorrectionData,
                                        final ImageProcessorCacheSpec cacheSpec,
                                        final Long minZToRender) {

        final List<Grid.Block> gridBlocks = buildGridBlocks(tileWidth, tileHeight, dimensions, blockSize, minZToRender);

        final JavaRDD<Grid.Block> rdd = sc.parallelize(gridBlocks);

        final Broadcast<ImageProcessorCacheSpec> broadcastCacheSpec = sc.broadcast(cacheSpec);

        rdd.foreach(gridBlock -> {

            final ImageProcessorCache ipCache = broadcastCacheSpec.getValue().getSharableInstance();

            /* assume we can fit it in an array */
            final ArrayImg<UnsignedShortType, ShortArray> block = ArrayImgs.unsignedShorts(gridBlock.dimensions);

            final Grid.Block translatedBlock = new Grid.Block(Intervals.translate(block, min), gridBlock.gridPosition);
            final long x = translatedBlock.min(0);
            final long y = translatedBlock.min(1);
            final long startZ = translatedBlock.min(2);

            // enable logging on executors and add gridBlock context to log messages
            LogUtilities.setupExecutorLog4j(x + ":" + y + ":" + startZ);

            ThicknessCorrectionData.LayerInterpolator priorInterpolator = null;
            ShortProcessor currentProcessor;
            ShortProcessor priorProcessor = null;
            ShortProcessor nextProcessor = null;
            for (int zIndex = 0; zIndex < block.dimension(2); zIndex++) {

                final long z = translatedBlock.min(2) + zIndex;

                if (thicknessCorrectionData == null) {
                    currentProcessor = boxRenderer.render(x, y, z, ipCache);
                } else {

                    final ThicknessCorrectionData.LayerInterpolator interpolator =
                            thicknessCorrectionData.getInterpolator(z);

                    if (priorInterpolator != null) {
                        if (interpolator.getPriorStackZ() == priorInterpolator.getNextStackZ()) {
                            priorProcessor = nextProcessor;
                            nextProcessor = null;
                        } else if (interpolator.getPriorStackZ() != priorInterpolator.getPriorStackZ()) {
                            priorProcessor = null;
                            nextProcessor = null;
                        } // else priorStackZ and nextStackZ have not changed, so reuse processors
                    }
                    priorInterpolator = interpolator;

                    if (priorProcessor == null) {
                        priorProcessor = boxRenderer.render(x, y, interpolator.getPriorStackZ(), ipCache);
//                    } else {
//                        LOG.info("priorProcessor already exists for z " + z + " (" + x + "," + y + ")");
                    }

                    if (interpolator.needsInterpolation()) {

                        currentProcessor = new ShortProcessor(priorProcessor.getWidth(), priorProcessor.getHeight());

                        if (nextProcessor == null) {
                            nextProcessor = boxRenderer.render(x, y, interpolator.getNextStackZ(), ipCache);
//                        } else {
//                            LOG.info("nextProcessor already exists for z " + z + " (" + x + "," + y + ")");
                        }

                        final int totalPixels = currentProcessor.getWidth() * currentProcessor.getHeight();
                        for (int pixelIndex = 0; pixelIndex < totalPixels; pixelIndex++) {
                            final double intensity = interpolator.deriveIntensity(priorProcessor.get(pixelIndex),
                                                                                  nextProcessor.get(pixelIndex));
                            currentProcessor.set(pixelIndex, (int) intensity);
                        }

                    } else {
                        currentProcessor = priorProcessor;
                    }

                }

                final IntervalView<UnsignedShortType> outSlice = Views.hyperSlice(block, 2, zIndex);
                final IterableInterval<UnsignedShortType> inSlice = Views
                        .flatIterable(
                                Views.interval(
                                        ArrayImgs.unsignedShorts(
                                                (short[]) currentProcessor.getPixels(),
                                                currentProcessor.getWidth(),
                                                currentProcessor.getHeight()),
                                        outSlice));

                final Cursor<UnsignedShortType> in = inSlice.cursor();
                final Cursor<UnsignedShortType> out = outSlice.cursor();
                while (out.hasNext()) {
                    out.next().set(in.next());
                }
            }

            final N5Writer anotherN5Writer = new N5FSWriter(n5Path); // needed to prevent Spark serialization error
            N5Utils.saveNonEmptyBlock(block, anotherN5Writer, datasetName, gridBlock.gridPosition, new UnsignedShortType(0));
        });
    }

}
