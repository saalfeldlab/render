package org.janelia.render.client.newexport;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.ByteBoxRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.Grid;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.multisem.ExponentialFitClient;
import org.janelia.render.client.newsolver.DistributedIntensityCorrectionSolver;
import org.janelia.render.client.newsolver.setup.IntensityCorrectionSetup;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

public class TobiasTest {

    private static final String BASE_URL = "http://em-services-1.int.janelia.org:8080/render-ws/v1";

    public static void main(final String[] args)
            throws Exception {

        final String owner = args.length < 1 ? "pietzscht" : args[0];
        final String project = args.length < 2 ? "w60_serial_290_to_299" : args[1];
        final String stack = args.length < 3 ? "w60_s296_r00_small_test_align" : args[2];

        final StackMetaData stackMetaData = RenderTools.openStackMetaData(BASE_URL, owner, project, stack);

        // renderAndShowTiles(owner, project, stack, stackMetaData);

        // TODO: you'll need to map nrs locally (on Mac use smb://nrsv.hhmi.org/hess and symbolically link /nrs/hess to /Volumes/hess)
        final String exponentialFitStack = runExponentialFit(owner,
                                                             project,
                                                             stack);

        final String intensityCorrectedStack = runIntensityCorrection(owner,
                                                                      project,
                                                                      exponentialFitStack);

        final String n5BasePath = "/Users/trautmane/Desktop/tobiasTest.n5";
        final String fullScaleDatasetName = "/" + intensityCorrectedStack + "/s0";
        exportN5(owner,
                 project,
                 intensityCorrectedStack,
                 stackMetaData.getStackBounds(),
                 n5BasePath,
                 fullScaleDatasetName);

        System.out.println("To view resulting n5:\n\nn5-view.sh -i " + n5BasePath + " -d " + fullScaleDatasetName);
    }

    @SuppressWarnings("unused")
    public static void renderAndShowTiles(final String owner,
                                          final String project,
                                          final String stack,
                                          final StackMetaData stackMetaData)
            throws Exception {

        final Bounds stackBounds = stackMetaData.getStackBounds();
        final Interval interval = RenderTools.stackBounds(stackMetaData);
        final long xMin = interval.min(0);
        final long yMin = interval.min(1);
        final long zMin = interval.min(2);
        final long zMax = interval.max(2);

        // hack to replace source paths with local paths:
        //   will change imageUrl values like file:/nrs/hess/ibeammsem/system_02/wafers/wafer_60/acquisition/scans/scan_020/slabs/slab_0399/mfovs/mfov_0014/sfov_001.png
        //   to file:/Users/trautmane/Desktop/local_wafer_60_scans/scan_020/slabs/slab_0399/mfovs/mfov_0014/sfov_001.png
        final Pattern hackSourcePathPattern = Pattern.compile("file:.*scans/");
        final String hackReplacementString = "file:/Users/trautmane/Desktop/local_wafer_60_scans/";

        // alternative:
        //   map nrs ( e.g. on Mac use smb://nrsv.hhmi.org/hess ) and symbolically link /nrs/hess to /Volumes/hess
        // final Pattern hackSourcePathPattern = null;
        // final String hackReplacementString = null;

        for (long z = zMin; z <= zMax; z++) {
            final ImageProcessorWithMasks ipwm =
                    renderUncachedImage(BASE_URL, owner, project, stack,
                                        xMin, yMin, z,
                                        stackBounds.getWidth(), stackBounds.getHeight(), 1.0,
                                        hackSourcePathPattern, hackReplacementString);
            final String title = "z_" + z + "_" + stack;
            final ImagePlus imagePlus = new ImagePlus(title, ipwm.ip);
            imagePlus.show();
        }
    }

    public static ImageProcessorWithMasks renderUncachedImage(final String baseUrl,
                                                              final String owner,
                                                              final String project,
                                                              final String stack,
                                                              final long minX,
                                                              final long minY,
                                                              final long z,
                                                              final long width,
                                                              final long height,
                                                              final double scale,
                                                              final Pattern hackSourcePathPattern,
                                                              final String hackReplacementString)
            throws IllegalArgumentException {

        final String renderParametersUrlString = String.format(RenderTools.renderParametersFormat,
                                                               baseUrl,
                                                               owner,
                                                               project,
                                                               stack,
                                                               z, // full res coordinates
                                                               minX, // full res coordinates
                                                               minY, // full res coordinates
                                                               width, // full res coordinates
                                                               height, // full res coordinates
                                                               scale);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrlString);

        renderParameters.getTileSpecs().forEach(ts -> {
            if ((hackSourcePathPattern != null) && (hackReplacementString != null)) {
                ts.replaceFirstChannelImageUrl(hackSourcePathPattern, hackReplacementString);
            }
            final File tile = new File(ts.getFirstMipmapEntry().getValue().getImageFilePath());
            if (! tile.exists()) {
                throw new IllegalArgumentException("tile id " + ts.getTileId() + " image file " + tile +
                                                   " does not exist, storage filesystem may not be mapped locally");
            }
        });

        return Renderer.renderImageProcessorWithMasks(renderParameters, ImageProcessorCache.DISABLED_CACHE);
    }

    public static String runExponentialFit(final String owner,
                                           final String project,
                                           final String sourceStack)
            throws IOException {

        final String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        final String targetStack = sourceStack + "_avgshd_" + timestamp;

        final String[] effectiveArgs = {
                "--baseDataUrl", BASE_URL,
                "--owner", owner,
                "--project", project,
                "--stack", sourceStack,
                "--targetStack", targetStack,
                "--completeTargetStack",
                };

        final ExponentialFitClient.Parameters parameters = new ExponentialFitClient.Parameters();
        parameters.parse(effectiveArgs);

        final ExponentialFitClient client = new ExponentialFitClient(parameters);
        client.process();

        return targetStack;
    }

    public static String runIntensityCorrection(final String owner,
                                                final String project,
                                                final String sourceStack)
            throws IOException {

        final String targetStack = sourceStack + "_ic";

        final String[] effectiveArgs = {
                "--baseDataUrl", BASE_URL,
                "--owner", owner,
                "--project", project,
                "--stack", sourceStack,
                "--targetStack", targetStack,
                "--threadsWorker", "1",
                "--threadsGlobal", "1",
                "--blockSizeZ", "1",
                "--completeTargetStack",
                "--zDistance", "0"
                };

        final IntensityCorrectionSetup cmdLineSetup = new IntensityCorrectionSetup();
        cmdLineSetup.parse(effectiveArgs);

        DistributedIntensityCorrectionSolver.run(cmdLineSetup);

        return targetStack;
    }

    /**
     * Copy of core render logic from org.janelia.render.client.spark.n5.N5Client
     * so that you can play with it without having to worry about Spark ...
     */
    public static void exportN5(final String owner,
                                final String project,
                                final String stack,
                                final Bounds stackBounds,
                                final String n5BasePath,
                                final String fullScaleDatasetName) {

        final long[] min = {
                stackBounds.getMinX().longValue(),
                stackBounds.getMinY().longValue(),
                stackBounds.getMinZ().longValue()
        };
        final long[] dimensions = {
                Double.valueOf(stackBounds.getDeltaX() + 1).longValue(),
                Double.valueOf(stackBounds.getDeltaY() + 1).longValue(),
                Double.valueOf(stackBounds.getDeltaZ() + 1).longValue()
        };
        final int tileSize = 2048;
        final int[] blockSize = { tileSize, tileSize, 2 };
        final List<Grid.Block> blockList = Grid.create(dimensions, blockSize, blockSize);

        final ByteBoxRenderer boxRenderer = new ByteBoxRenderer(BASE_URL,
                                                                owner,
                                                                project,
                                                                stack,
                                                                tileSize,
                                                                tileSize,
                                                                1.0,
                                                                null,
                                                                null,
                                                                false);


        try (final N5Writer n5Writer = new N5FSWriter(n5BasePath)) {

            n5Writer.createDataset(fullScaleDatasetName,
                                   dimensions,
                                   blockSize,
                                   DataType.UINT8,
                                   new GzipCompression());

            for (final Grid.Block gridBlock : blockList) {
                final ArrayImg<UnsignedByteType, ByteArray> block = ArrayImgs.unsignedBytes(gridBlock.dimensions);
                final Grid.Block translatedBlock = new Grid.Block(Intervals.translate(gridBlock, min),
                                                                  gridBlock.gridPosition);
                final long x = translatedBlock.min(0);
                final long y = translatedBlock.min(1);

                ByteProcessor currentProcessor;
                for (int zIndex = 0; zIndex < block.dimension(2); zIndex++) {

                    final long z = translatedBlock.min(2) + zIndex;
                    currentProcessor = boxRenderer.render(x, y, z, ImageProcessorCache.DISABLED_CACHE);

                    final IntervalView<UnsignedByteType> outSlice = Views.hyperSlice(block, 2, zIndex);
                    final IterableInterval<UnsignedByteType> inSlice = Views
                            .flatIterable(
                                    Views.interval(
                                            ArrayImgs.unsignedBytes(
                                                    (byte[]) currentProcessor.getPixels(),
                                                    currentProcessor.getWidth(),
                                                    currentProcessor.getHeight()),
                                            outSlice));

                    final Cursor<UnsignedByteType> in = inSlice.cursor();
                    final Cursor<UnsignedByteType> out = outSlice.cursor();
                    while (out.hasNext()) {
                        out.next().set(in.next());
                    }
                }

                N5Utils.saveNonEmptyBlock(block,
                                          n5Writer,
                                          fullScaleDatasetName,
                                          gridBlock.gridPosition,
                                          new UnsignedByteType(0));
            }
        }

    }

}
