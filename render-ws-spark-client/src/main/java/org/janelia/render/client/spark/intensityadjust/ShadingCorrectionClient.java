package org.janelia.render.client.spark.intensityadjust;

import com.beust.jcommander.Parameter;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.util.Grid;
import org.janelia.render.client.ClientRunner;
import org.janelia.alignment.filter.emshading.ShadingModel;
import org.janelia.render.client.emshading.ShadingCorrection_Plugin;
import org.janelia.render.client.emshading.ShadingModelProvider;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Spark client for shading correction by a layer-wise quadratic or fourth order model.
 * The client takes as input an N5 container with a 3D 16bit dataset and a parameter file, and writes the corrected data
 * to a new dataset in a given container.
 * </p>
 * The parameter file is a json file containing a list of z values and corresponding models. The model for each z value
 * is valid for all z layers starting at the given z value until the next z value in the list.
 * Models are specified by an identifier ("quadratic" or "fourthOrder") and a list of coefficients (6 or 9,
 * respectively). Coefficients can be found interactively using {@link ShadingCorrection_Plugin}.
 * </p>
 * In particular, the parameter file should have the following format. There is one root array, whose elements have
 * exactly keys: "fromZ", "modelType", and "coefficients"s, e.g.:
 * <pre>
 * [ {
 *     "fromZ": 1,
 *     "modelType": "quadratic",
 *     "coefficients": [ 1.0, 1.0, 1.0, 1.0, 1.0, 1.0 ]
 *   }, {
 *     "fromZ": 3,
 *     "modelType": "fourthOrder",
 *     "coefficients": [ 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0 ]
 *   }
 * ]
 * </pre>
 */
public class ShadingCorrectionClient implements Serializable {

    public static class Parameters extends CommandLineParameters {
        @Parameter(names = "--n5In",
                description = "Path to the input N5 container",
                required = true)
        public String n5In;

        @Parameter(names = "--datasetIn",
                description = "Name of the input dataset (or group in case of multiscale pyramid; only s0 is processed)",
                required = true)
        public String datasetIn;

        @Parameter(names = "--mask",
                description = "Name of the mask dataset for the input (or group in case of multiscale pyramid; only s0 is processed)",
                required = true)
        public String mask;

        @Parameter(names = "--n5Out",
                description = "Path to the output N5 container",
                required = true)
        public String n5Out;

        @Parameter(names = "--datasetOut",
                description = "Name of the output dataset (or group in case of multiscale pyramid; only s0 is processed)",
                required = true)
        public String datasetOut;

        @Parameter(names = "--parameterFile",
                description = "Path to the parameter json file containing a list of z values and their corresponding models. See class description for details.",
                required = true)
        public String parameterFile;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ShadingCorrectionClient.class);

    private final Parameters parameters;

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final ShadingCorrectionClient client = new ShadingCorrectionClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }


    public ShadingCorrectionClient(final Parameters parameters) {
        LOG.info("init: parameters={}", parameters);
        this.parameters = parameters;
    }

    public void run() throws IOException {
        final SparkConf conf = new SparkConf().setAppName("ShadingCorrectionClient");
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            runWithContext(sparkContext);
        }
    }

    public void runWithContext(final JavaSparkContext sparkContext) throws IOException {

        LOG.info("runWithContext: entry");

        // read parameters
        final ShadingModelProvider modelProvider = ShadingModelProvider.fromJsonFile(parameters.parameterFile);

        // set up input and output N5 datasets
        final int[] blockSize;
        final long[] dimensions;
        try (final N5Reader in = new N5FSReader(parameters.n5In)) {
            if (!in.exists(parameters.datasetIn) || !in.exists(parameters.mask)) {
                throw new IllegalArgumentException("Mask dataset (" + parameters.mask + ") or input dataset (" + parameters.datasetIn + ") do not exist");
            }

            try (final N5Writer out = new N5FSWriter(parameters.n5Out)) {
                if (out.exists(parameters.datasetOut)) {
                    throw new IllegalArgumentException("Output dataset already exists: " + parameters.datasetOut);
                }

                // create output dataset (or group if input is multiscale)
                final boolean isMultiScale = in.exists(parameters.datasetIn + "/s0");
                cloneDatasetOrGroupAttributes(in, out, parameters, isMultiScale);
                out.setAttribute(parameters.datasetOut, "BackgroundCorrectionClientParameters", parameters);

                if (isMultiScale) {
                    // since we only write s0, we need to set the scales attribute accordingly
                    out.setAttribute(parameters.datasetOut, "scales", new int[][]{{1, 1, 1}});

                    // also set up the output dataset for s0
                    parameters.datasetIn = parameters.datasetIn + "/s0";
                    parameters.mask = parameters.mask + "/s0";
                    parameters.datasetOut = parameters.datasetOut + "/s0";
                    cloneDatasetOrGroupAttributes(in, out, parameters, false);
                }

                // read block size and dimensions (parameters now refer to the actual dataset to be processed)
                final DatasetAttributes inputAttributes = in.getDatasetAttributes(parameters.datasetIn);
                blockSize = inputAttributes.getBlockSize();
                dimensions = inputAttributes.getDimensions();
            }
        }

        // parallelize computation over blocks of the input/output dataset
        final Broadcast<ShadingModelProvider> modelProviderBroadcast = sparkContext.broadcast(modelProvider);
        final Broadcast<Parameters> parametersBroadcast = sparkContext.broadcast(parameters);

        final List<Grid.Block> blocks = Grid.create(dimensions, blockSize);
        final JavaRDD<Grid.Block> blockRDD = sparkContext.parallelize(blocks);

        blockRDD.foreach(block -> processSingleBlock(parametersBroadcast.value(), modelProviderBroadcast.value(), block));

        LOG.info("runWithContext: exit");
    }

    private static void cloneDatasetOrGroupAttributes(final N5Reader in, final N5Writer out, final Parameters parameters, final boolean isGroup) {
        if (isGroup) {
            out.createGroup(parameters.datasetOut);
        } else {
            final DatasetAttributes datasetAttributes = in.getDatasetAttributes(parameters.datasetIn);
            out.createDataset(parameters.datasetOut, datasetAttributes);
        }

        final Map<String, Class<?>> attributes = in.listAttributes(parameters.datasetIn);
        attributes.forEach((key, clazz) -> {
            final Object attribute = in.getAttribute(parameters.datasetIn, key, clazz);
            out.setAttribute(parameters.datasetOut, key, attribute);
        });
    }

    private static void processSingleBlock(final Parameters parameters, final ShadingModelProvider modelProvider, final Grid.Block block) {
        // enable logging on executors and add block context to log messages
        LogUtilities.setupExecutorLog4j(block.gridPosition[0] + ":" + block.gridPosition[1] + ":" + block.gridPosition[2]);

        LOG.info("processSingleBlock: entry");

        try (final N5Reader in = new N5FSReader(parameters.n5In);
             final N5Writer out = new N5FSWriter(parameters.n5Out)) {

            // load block
            final Img<UnsignedShortType> img = N5Utils.open(in, parameters.datasetIn);
            final RandomAccessibleInterval<UnsignedShortType> croppedImg = Views.interval(img, block);
            final Img<UnsignedByteType> mask = N5Utils.open(in, parameters.mask);
            final RandomAccessibleInterval<UnsignedByteType> croppedMask = Views.interval(mask, block);
            final long[] dimensions = in.getDatasetAttributes(parameters.datasetIn).getDimensions();

            // process block z-slice by z-slice
            for (int z = (int) block.min(2); z <= block.max(2); z++) {
                final ShadingModel model = modelProvider.getModel(z);
                if (model == null) {
                    // no model for this z value
                    continue;
                }

                final RandomAccessibleInterval<UnsignedShortType> imgSlice = Views.hyperSlice(croppedImg, 2, z - (int) block.min(2));
                final RandomAccessibleInterval<UnsignedByteType> maskSlice = Views.hyperSlice(croppedMask, 2, z - (int) block.min(2));
                final Cursor<UnsignedShortType> imgCursor = Views.iterable(imgSlice).localizingCursor();
                final Cursor<UnsignedByteType> maskCursor = Views.iterable(maskSlice).localizingCursor();
                final double[] location = new double[2];

                // apply model to slice
                while (imgCursor.hasNext()) {
                    final UnsignedShortType pixel = imgCursor.next();
                    final UnsignedByteType maskPixel = maskCursor.next();
                    if (maskPixel.get() == 0) {
                        // don't apply model to background
                        continue;
                    }

                    location[0] = ShadingModel.toModelCoordinates(imgCursor.getDoublePosition(0), 0, dimensions[0]);
                    location[1] = ShadingModel.toModelCoordinates(imgCursor.getDoublePosition(1), 0, dimensions[1]);

                    model.applyInPlace(location);
                    pixel.setReal(UnsignedShortType.getCodedSignedShortChecked((int) (pixel.getRealDouble() - location[0])));
                }
            }

            // save block
            N5Utils.saveNonEmptyBlock(croppedImg, out, parameters.datasetOut, block.gridPosition, new UnsignedShortType());
        }
    }
}
