package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import mpicbg.trakem2.transform.ThinPlateSplineTransform;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jitk.spline.ThinPlateR2LogRSplineKernelTransform;

/**
 * Java client for translating "raw" landmark (control point) values into a
 * {@link mpicbg.trakem2.transform.ThinPlateSplineTransform} specification
 * suitable for use (and storage) by the render services.
 *
 * @author Dan Kapner
 * @author Eric Trautman
 */
public class ThinPlateSplineClient {

    public static class Parameters extends CommandLineParameters {

        @Parameter(
                names = "--numberOfLandmarks",
                description = "Number of landmark points specified for each source and target",
                required = true)
        public Integer numberOfLandmarks;

        @Parameter(
                names = "--numberOfDimensions",
                description = "Number of dimensions",
                required = false)
        public Integer numberOfDimensions = 2;

        @Parameter(
                names = "--computeAffine",
                description = "Indicates whether affine should be computed",
                required = false,
                arity = 1)
        public Boolean computeAffine = true;

        @Parameter(
                names = "--outputFile",
                description = "Full path of JSON file for result transform spec (if omitted, spec is simply logged)",
                required = false)
        public String outputFile;

        @Parameter(
                description = "px1 py1 px2 py2 ... pxnlm pynlm qx1 qy1 qx2 qy2 ... qxnlm qynlm",
                required = true)
        public List<Double> landmarkValues;

        public void validate()
                throws IllegalArgumentException {

            final int expectedNumberOfValues = numberOfLandmarks * numberOfDimensions * 2;

            if (landmarkValues.size() != expectedNumberOfValues) {
                throw new IllegalArgumentException(
                        landmarkValues.size() + " values were specified when " + expectedNumberOfValues +
                        " were expected.  Values for each source landmark and dimension " +
                        "and each corresponding target landmark and dimension should be specified.");
            }

        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ThinPlateSplineClient client = new ThinPlateSplineClient(parameters);
                final String transformJson = client.buildTransformSpec().toJson();

                if (parameters.outputFile != null) {
                    final Path outputPath = Paths.get(parameters.outputFile).toAbsolutePath();
                    Files.write(outputPath, transformJson.getBytes());
                    LOG.info("runClient: saved transform spec in {}", outputPath);
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public ThinPlateSplineClient(final Parameters parameters)
            throws IllegalArgumentException {
        this.parameters = parameters;
        parameters.validate();
    }

    public LeafTransformSpec buildTransformSpec() throws Exception {

        final double[][] sourcePoints = new double[parameters.numberOfDimensions][parameters.numberOfLandmarks];
        final double[][] targetPoints = new double[parameters.numberOfDimensions][parameters.numberOfLandmarks];

        int sourceValueIndex = 0;
        int targetValueIndex = parameters.numberOfLandmarks * parameters.numberOfDimensions;

        for (int landmark = 0; landmark < parameters.numberOfLandmarks; landmark++){
            for (int dimension = 0; dimension < parameters.numberOfDimensions; dimension++) {
                sourcePoints[dimension][landmark] = parameters.landmarkValues.get(sourceValueIndex);
                targetPoints[dimension][landmark] = parameters.landmarkValues.get(targetValueIndex);
                sourceValueIndex++;
                targetValueIndex++;
            }
        }

        final ThinPlateR2LogRSplineKernelTransform kernelTransform =
                new ThinPlateR2LogRSplineKernelTransform(parameters.numberOfDimensions, sourcePoints, targetPoints);

        kernelTransform.setDoAffine(parameters.computeAffine);
        kernelTransform.solve();

        final ThinPlateSplineTransform serializableTransform = new ThinPlateSplineTransform(kernelTransform);

        final LeafTransformSpec transformSpec = new LeafTransformSpec(serializableTransform.getClass().getName(),
                                                                      serializableTransform.toDataString());

        LOG.info("buildTransformSpec: returning\n{}", transformSpec.toJson());

        return transformSpec;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ThinPlateSplineClient.class);
}
