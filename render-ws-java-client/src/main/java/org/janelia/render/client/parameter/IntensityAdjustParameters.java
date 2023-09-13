package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;

import java.util.List;

/**
 * Parameters for rendering intensity adjusted montage scapes for layers within a stack.
 *
 * @author Eric Trautman
 */
@Parameters
public class IntensityAdjustParameters
        extends CommandLineParameters {

    public enum StrategyName {
        AFFINE, FIRST_LAYER_QUADRATIC, ALL_LAYERS_QUADRATIC
    }

    @ParametersDelegate
    public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

    @ParametersDelegate
    public AlgorithmicIntensityAdjustParameters algorithmic = new AlgorithmicIntensityAdjustParameters();

    @ParametersDelegate
    public ZRangeParameters layerRange = new ZRangeParameters();

    @Parameter(
            names = "--intensityCorrectedFilterStack",
            description = "Name of stack to store tile specs with intensity corrected filter data",
            required = true)
    public String intensityCorrectedFilterStack;

    @Parameter(
            names = "--completeCorrectedStack",
            description = "Complete the intensity corrected stack after processing",
            arity = 0)
    public boolean completeCorrectedStack = false;

    @Parameter(
            names = "--z",
            description = "Explicit z values for sections to be processed",
            variableArity = true) // e.g. --z 20.0 21.0 22.0
    public List<Double> zValues;

    @Parameter(
            names = "--strategy",
            description = "Strategy to use")
    public StrategyName strategyName = StrategyName.AFFINE;

    @Parameter(
            names = "--numThreads",
            description = "Number of threads to use")
    public Integer numThreads = 1;

    public long getMaxNumberOfCachedPixels() {
        return algorithmic.maxPixelCacheGb * 1_000_000_000L;
    }
}