package org.janelia.render.client.intensityadjust;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.janelia.render.client.parameter.IntensityAdjustParameters.CorrectionMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering intensity adjusted montage scapes for layers within a stack.
 *
 * @author Eric Trautman
 */
public class IntensityAdjustedScapeClient
        implements Serializable {

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final IntensityAdjustParameters parameters = new IntensityAdjustParameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final IntensityAdjustedScapeClient client = new IntensityAdjustedScapeClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final IntensityAdjustParameters parameters;

    private IntensityAdjustedScapeClient(final IntensityAdjustParameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws Exception {

        LOG.info("run: entry");

        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                      parameters.layerRange.minZ,
                                                                      parameters.layerRange.maxZ,
                                                                      parameters.zValues);
        if (zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final File sectionRootDirectory = parameters.getSectionRootDirectory(new Date());
        FileUtil.ensureWritableDirectory(sectionRootDirectory);

        final StackMetaData stackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        final String slicePathFormatSpec = parameters.getSlicePathFormatSpec(stackMetaData,
                                                                             sectionRootDirectory);
        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();

        for (final Double z : zValues) {
            renderIntensityAdjustedScape(sourceDataClient,
                                         parameters.stack,
                                         stackBounds,
                                         parameters.correctionMethod,
                                         slicePathFormatSpec,
                                         parameters.format,
                                         z.intValue());
        }

        LOG.info("run: exit, rendered {} layers", zValues.size());
    }

    public static void renderIntensityAdjustedScape(final RenderDataClient dataClient,
                                                    final String stack,
                                                    final Bounds stackBounds,
                                                    final CorrectionMethod correctionMethod,
                                                    final String slicePathFormatSpec,
                                                    final String format,
                                                    final int integralZ)
            throws Exception {

        LOG.info("renderIntensityAdjustedScape: entry, integralZ={}", integralZ);

        final String parametersUrl =
                dataClient.getRenderParametersUrlString(stack,
                                                        stackBounds.getMinX(),
                                                        stackBounds.getMinY(),
                                                        integralZ,
                                                        (int) (stackBounds.getDeltaX() + 0.5),
                                                        (int) (stackBounds.getDeltaY() + 0.5),
                                                        1.0,
                                                        null);

        final RenderParameters sliceRenderParameters = RenderParameters.loadFromUrl(parametersUrl);

        // make cache large enough to hold shared mask processors
        final ImageProcessorCache imageProcessorCache =
                new ImageProcessorCache(15_000L * 15_000L,
                                        false,
                                        false);

        final ImageProcessorWithMasks slice;
        switch (correctionMethod) {
//            case GAUSS:
//            case GAUSS_WEIGHTED:
//                slice = AdjustBlock.renderIntensityAdjustedSliceGauss(stack,
//                                                                      dataClient,
//                                                                      interval,
//                                                                      CorrectionMethod.GAUSS_WEIGHTED.equals(correctionMethod),
//                                                                      false,
//                                                                      integralZ);
//
//                break;
            case GLOBAL_PER_SLICE:
                slice = AdjustBlock.renderIntensityAdjustedSliceGlobalPerSlice(stack,
                                                                               dataClient,
                                                                               sliceRenderParameters,
                                                                               imageProcessorCache,
                                                                               integralZ);
                break;
            default:
                throw new UnsupportedOperationException("only support GLOBAL_PER_SLICE for hack");
//                slice = AdjustBlock.renderIntensityAdjustedSlice(stack,
//                                                                 dataClient,
//                                                                 interval,
//                                                                 1.0,
//                                                                 false,
//                                                                 integralZ);
//                break;
        }

        final BufferedImage sliceImage = slice.ip.getBufferedImage();

        final String slicePath = String.format(slicePathFormatSpec, integralZ);

        Utils.saveImage(sliceImage, slicePath, format, false, 0.85f);
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityAdjustedScapeClient.class);
}
