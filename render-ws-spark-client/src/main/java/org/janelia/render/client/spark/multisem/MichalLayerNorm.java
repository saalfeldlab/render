package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.process.ImageProcessor;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.LutFilter;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Tuple2;

/**
 * Spark client that normalizes per-layer intensity statistics by histogram-matching every
 * z-layer of every input stack to a reference layer (configurable; defaults to the first z
 * of each stack). For each layer, only pixels strictly above a configurable threshold contribute
 * to the histogram, which excludes resin/background. The resulting 256-entry LUT is added to
 * every tile spec in the layer as a {@link LutFilter} (composed with any preexisting filter)
 * and the tiles are written to a derived target stack.
 *
 * <p>Parallelism spans every (stack, z) pair across all input stacks in a single Spark stage,
 * rather than per-stack, so total parallelism is bounded by total layers across all stacks.
 *
 * @author Michael Innerberger
 */
public class MichalLayerNorm implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @Parameter(names = "--targetStackSuffix", description = "Suffix to append to each source stack name for the output stack")
        public String targetStackSuffix = "_norm";

        @Parameter(names = "--threshold", description = "Only pixels with value strictly greater than this contribute to the histogram")
        public int threshold = 100;

        @Parameter(names = "--referenceZ", description = "z-value of the reference layer (applied to every stack). When omitted, the first layer is used")
        public double referenceZ = 1.0;

        @Parameter(names = "--completeTargetStack", description = "If true, target stacks are marked COMPLETE after processing")
        public boolean completeTargetStack = false;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] runArgs) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(runArgs);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MichalLayerNorm client = new MichalLayerNorm();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    public MichalLayerNorm() {
    }

    public void createContextAndRun(final Parameters parameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            normalizeAllStacks(sparkContext, parameters);
        }
    }

    private void normalizeAllStacks(final JavaSparkContext sparkContext,
                                    final Parameters parameters) throws IOException {

        final String baseDataUrl = parameters.multiProject.getBaseDataUrl();
        final List<StackWithZValues> allStacks = parameters.multiProject.buildListOfStackWithAllZ();

        if (allStacks.isEmpty()) {
            LOG.warn("normalizeAllStacks: no stacks resolved from parameters, nothing to do");
            return;
        }

        // Driver-side: set up derived stacks and build the flat (stack, z) work list.
        final double referenceZ = parameters.referenceZ;
        final String targetStackSuffix = parameters.targetStackSuffix;
        final Set<StackId> stackIds = new HashSet<>();
        final List<StackAndZ> allLayers = new ArrayList<>();

        for (final StackWithZValues stackWithAllZ : allStacks) {
            final StackId sourceStackId = stackWithAllZ.getStackId();
            final List<Double> zValues = stackWithAllZ.getzValues();
            if (zValues.isEmpty()) {
                LOG.warn("normalizeAllStacks: stack {} has no z values, skipping", sourceStackId.toDevString());
                continue;
            }
            if (! zValues.contains(referenceZ)) {
                throw new IllegalArgumentException("reference z " + referenceZ +
                                                   " is not present in stack " + sourceStackId.toDevString());
            }
            stackIds.add(sourceStackId);

            final RenderDataClient driverClient = new RenderDataClient(baseDataUrl,
                                                                       sourceStackId.getOwner(),
                                                                       sourceStackId.getProject());
            final StackMetaData sourceStackMetaData = driverClient.getStackMetaData(sourceStackId.getStack());
            driverClient.setupDerivedStack(sourceStackMetaData, sourceStackId.getStack() + targetStackSuffix);

            for (final Double z : zValues) {
                allLayers.add(new StackAndZ(sourceStackId, z));
            }
        }

        if (allLayers.isEmpty()) {
            LOG.warn("normalizeAllStacks: no (stack, z) work items, exiting");
            return;
        }

        LOG.info("normalizeAllStacks: phase 1 - computing histograms for {} layers across {} stacks",
                 allLayers.size(), stackIds.size());

        final int threshold = parameters.threshold;
        final JavaPairRDD<StackAndZ, long[]> rddHistograms =
                sparkContext.parallelize(allLayers).mapToPair(
                        sz -> new Tuple2<>(sz, computeLayerHistogram(baseDataUrl, sz.stackId, sz.z, threshold)));
        final Map<StackAndZ, long[]> histogramsByKey = new HashMap<>(rddHistograms.collectAsMap());

        LOG.info("normalizeAllStacks: phase 2 - building LUTs on driver");

        final Map<StackAndZ, int[]> lutsByKey = new HashMap<>();
        for (final StackAndZ sz : allLayers) {
            final long[] referenceHist = histogramsByKey.get(new StackAndZ(sz.stackId, referenceZ));
            if (referenceHist == null) {
                throw new IllegalStateException("missing reference histogram for stack " + sz.stackId.toDevString());
            }
            lutsByKey.put(sz, buildLut(referenceHist, histogramsByKey.get(sz), threshold));
        }

        LOG.info("normalizeAllStacks: phase 3 - applying LUTs and saving tiles");

        final Broadcast<Map<StackAndZ, int[]>> bcLuts = sparkContext.broadcast(lutsByKey);
        final JavaRDD<StackAndZ> rddLayers = sparkContext.parallelize(allLayers);
        rddLayers.foreach(sz -> applyLutAndSave(
                baseDataUrl, sz.stackId, sz.stackId.getStack() + targetStackSuffix, sz.z, bcLuts.value().get(sz)));

        if (parameters.completeTargetStack) {
            for (final StackId stackId : stackIds) {
                final RenderDataClient driverClient = new RenderDataClient(baseDataUrl,
                                                                           stackId.getOwner(),
                                                                           stackId.getProject());
                driverClient.setStackState(stackId.getStack() + targetStackSuffix,
                                           StackMetaData.StackState.COMPLETE);
            }
        }

        LOG.info("normalizeAllStacks: exit");
    }

    private static long[] computeLayerHistogram(final String baseDataUrl,
                                                final StackId stackId,
                                                final double z,
                                                final int threshold) throws IOException {

        LogUtilities.setupExecutorLog4j(stackId.toDevString() + "::z" + z);

        final RenderDataClient executorClient = new RenderDataClient(baseDataUrl,
                                                                     stackId.getOwner(),
                                                                     stackId.getProject());
        final ResolvedTileSpecCollection tiles = executorClient.getResolvedTiles(stackId.getStack(), z);
        final ImageProcessorCache cache = new ImageProcessorCache(1_000_000_000L, false, false);

        final long[] hist = new long[256];
        for (final TileSpec ts : tiles.getTileSpecs()) {
            final ImageProcessor ip = loadImageProcessor(cache, ts);
            final int n = ip.getWidth() * ip.getHeight();
            for (int i = 0; i < n; i++) {
                final int v = ip.get(i) & 0xff;
                if (v > threshold) {
                    hist[v]++;
                }
            }
        }
        LOG.info("computeLayerHistogram: stack {} z {} processed {} tiles", stackId.toDevString(), z, tiles.getTileCount());
        return hist;
    }

    private static void applyLutAndSave(final String baseDataUrl,
                                        final StackId stackId,
                                        final String targetStack,
                                        final double z,
                                        final int[] lut) throws IOException {

        LogUtilities.setupExecutorLog4j(stackId.toDevString() + "::z" + z);

        final RenderDataClient executorClient = new RenderDataClient(baseDataUrl,
                                                                     stackId.getOwner(),
                                                                     stackId.getProject());
        final ResolvedTileSpecCollection tiles = executorClient.getResolvedTiles(stackId.getStack(), z);

        final FilterSpec lutSpec = FilterSpec.forFilter(new LutFilter(lut));
        for (final TileSpec ts : tiles.getTileSpecs()) {
            ts.addFilterSpec(lutSpec);
        }
        executorClient.saveResolvedTiles(tiles, targetStack, z);
    }

    private static ImageProcessor loadImageProcessor(final ImageProcessorCache cache,
                                                     final TileSpec tileSpec) {
        final ImageAndMask imageAndMask = tileSpec.getFirstMipmapEntry().getValue();
        return cache.get(imageAndMask.getImageUrl(),
                         0,
                         false,
                         false,
                         imageAndMask.getImageLoaderType(),
                         0);
    }

    /**
     * Build a 256-entry CDF-matching LUT mapping the {@code source} histogram to the
     * {@code reference} histogram. The above-threshold range is filled by CDF matching;
     * the below-threshold range (which has no histogram data) is filled by a monotone
     * cubic (PCHIP) "sigmoid" curve that is anchored at (0, 0), pulled toward identity in
     * the lower half via a (2T/3, T/3) control point, and matched to the above-threshold
     * LUT shape just past the threshold so the LUT stays continuous and monotone across the
     * boundary. This mirrors the {@code --sub-threshold sigmoid} path of the prototype
     * {@code match_histograms.py}. All entries are clipped to [0, 255]. Returns the identity
     * LUT if either histogram is empty.
     */
    static int[] buildLut(final long[] reference, final long[] source, final int threshold) {
        final int[] lut = new int[256];

        final double refTotal = sum(reference);
        final double srcTotal = sum(source);
        if (refTotal == 0 || srcTotal == 0) {
            LOG.warn("buildLut: degenerate histogram (refTotal={}, srcTotal={}), using identity LUT",
                     refTotal, srcTotal);
            for (int v = 0; v < 256; v++) {
                lut[v] = v;
            }
            return lut;
        }

        final double[] cdfRef = normalizedCdf(reference, refTotal);
        final double[] cdfSrc = normalizedCdf(source, srcTotal);

        // CDF-matched portion (v > threshold).
        final int firstMappedV = Math.min(255, threshold + 1);
        int j = firstMappedV;
        for (int v = firstMappedV; v < 256; v++) {
            final double target = cdfSrc[v];
            while (j < 255 && cdfRef[j] < target) {
                j++;
            }
            lut[v] = Math.min(255, j);
        }

        extendSubThresholdSigmoid(lut, threshold);
        return lut;
    }

    /**
     * Fill {@code lut[0..threshold]} in place with the "sigmoid" sub-threshold curve from
     * {@code match_histograms.py}: a monotone cubic (PCHIP) interpolation through the control
     * points (0, 0), (2T/3, T/3), and (for each offset in {1, 6, 16} whose intensity is &le;
     * 255) (T+off, lut[T+off]) drawn from the already-computed above-threshold mapping. The
     * y-values are made non-decreasing (running max) before interpolating, and results are
     * rounded and clamped to [0, 255].
     */
    private static void extendSubThresholdSigmoid(final int[] lut, final int threshold) {
        final double h = threshold / 3.0;
        final List<Double> xsList = new ArrayList<>();
        final List<Double> ysList = new ArrayList<>();
        xsList.add(0.0);          ysList.add(0.0);
        xsList.add(2.0 * h);      ysList.add(h);
        for (final int off : new int[] {1, 6, 16}) {
            final int x = threshold + off;
            if (x <= 255) {
                xsList.add((double) x);
                ysList.add((double) lut[x]);
            }
        }

        final int n = xsList.size();
        final double[] xs = new double[n];
        final double[] ys = new double[n];
        double runningMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            xs[i] = xsList.get(i);
            // np.maximum.accumulate: enforce a non-decreasing y sequence.
            runningMax = Math.max(runningMax, ysList.get(i));
            ys[i] = runningMax;
        }

        final double[] slopes = pchipSlopes(xs, ys);
        for (int v = 0; v < firstMappedV(threshold); v++) {
            // Math.rint matches numpy's round-half-to-even (np.rint) used by match_histograms.py.
            final double y = Math.rint(pchipEval(xs, ys, slopes, v));
            lut[v] = (int) Math.max(0, Math.min(255, y));
        }
    }

    private static int firstMappedV(final int threshold) {
        return Math.min(255, threshold + 1);
    }

    /**
     * Compute Fritsch-Carlson derivatives for a monotone cubic (PCHIP) interpolant,
     * matching scipy's {@code PchipInterpolator}. {@code x} must be strictly increasing
     * with at least three points.
     */
    private static double[] pchipSlopes(final double[] x, final double[] y) {
        final int n = x.length;
        final double[] hk = new double[n - 1];
        final double[] mk = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            hk[i] = x[i + 1] - x[i];
            mk[i] = (y[i + 1] - y[i]) / hk[i];
        }

        final double[] d = new double[n];

        // Interior points: zero at sign changes / flats, weighted harmonic mean otherwise.
        for (int i = 1; i < n - 1; i++) {
            if (Math.signum(mk[i - 1]) != Math.signum(mk[i]) || mk[i - 1] == 0.0 || mk[i] == 0.0) {
                d[i] = 0.0;
            } else {
                final double w1 = 2.0 * hk[i] + hk[i - 1];
                final double w2 = hk[i] + 2.0 * hk[i - 1];
                d[i] = (w1 + w2) / (w1 / mk[i - 1] + w2 / mk[i]);
            }
        }

        d[0] = pchipEdge(hk[0], hk[1], mk[0], mk[1]);
        d[n - 1] = pchipEdge(hk[n - 2], hk[n - 3], mk[n - 2], mk[n - 3]);
        return d;
    }

    /** One-sided three-point endpoint derivative with shape preservation (scipy _edge_case). */
    private static double pchipEdge(final double h0, final double h1, final double m0, final double m1) {
        double d = ((2.0 * h0 + h1) * m0 - h0 * m1) / (h0 + h1);
        if (Math.signum(d) != Math.signum(m0)) {
            d = 0.0;
        } else if (Math.signum(m0) != Math.signum(m1) && Math.abs(d) > 3.0 * Math.abs(m0)) {
            d = 3.0 * m0;
        }
        return d;
    }

    /** Evaluate the cubic Hermite interpolant defined by (x, y, slopes) at query point q. */
    private static double pchipEval(final double[] x, final double[] y, final double[] d, final double q) {
        int k = 0;
        while (k < x.length - 2 && q >= x[k + 1]) {
            k++;
        }
        final double h = x[k + 1] - x[k];
        final double s = (q - x[k]) / h;
        final double s2 = s * s;
        final double s3 = s2 * s;
        final double h00 = 2.0 * s3 - 3.0 * s2 + 1.0;
        final double h10 = s3 - 2.0 * s2 + s;
        final double h01 = -2.0 * s3 + 3.0 * s2;
        final double h11 = s3 - s2;
        return y[k] * h00 + h * d[k] * h10 + y[k + 1] * h01 + h * d[k + 1] * h11;
    }

    private static double sum(final long[] hist) {
        double total = 0;
        for (final long c : hist) {
            total += c;
        }
        return total;
    }

    private static double[] normalizedCdf(final long[] hist, final double total) {
        final double[] cdf = new double[256];
        long running = 0;
        for (int i = 0; i < 256; i++) {
            running += hist[i];
            cdf[i] = running / total;
        }
        return cdf;
    }

    /** Composite key identifying one (stack, z) layer for Spark RDDs and broadcasts. */
    public static class StackAndZ implements Serializable {

        public final StackId stackId;
        public final double z;

        public StackAndZ(final StackId stackId, final double z) {
            this.stackId = stackId;
            this.z = z;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof StackAndZ)) return false;
            final StackAndZ other = (StackAndZ) o;
            return Double.compare(other.z, z) == 0 && Objects.equals(stackId, other.stackId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stackId, z);
        }

        @Override
        public String toString() {
            return stackId.toDevString() + "::z" + z;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MichalLayerNorm.class);
}
