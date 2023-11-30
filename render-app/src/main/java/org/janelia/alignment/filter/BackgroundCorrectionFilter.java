package org.janelia.alignment.filter;

import ij.ImagePlus;
import ij.measure.Measurements;
import ij.plugin.ImageCalculator;
import ij.plugin.Scaler;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.RankFilters;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

public class BackgroundCorrectionFilter implements Filter {

    public enum SmoothMethod {
        GAUSSIAN((ip, radius) -> {
            final GaussianBlur gaussianBlur = new GaussianBlur();
            gaussianBlur.blurGaussian(ip, radius);
            return null;
        }),
        MEDIAN((ip, radius) -> {
            final RankFilters rankFilters = new RankFilters();
            rankFilters.rank(ip, radius, RankFilters.MEDIAN);
            return null;
        });

        private final BiFunction<ImageProcessor, Double, Void> smoother;

        SmoothMethod(final BiFunction<ImageProcessor, Double, Void> smoother) {
            this.smoother = smoother;
        }

        public void apply(final ImageProcessor ip, final double radius) {
            smoother.apply(ip, radius);
        }

        public static SmoothMethod fromString(final String method) {
            for (final SmoothMethod smoothMethod : values()) {
                if (smoothMethod.name().equalsIgnoreCase(method)) {
                    return smoothMethod;
                }
            }
            throw new IllegalArgumentException("Unknown smooth method: " + method);
        }
    }

    private double radius;
    private double downSamplingFactor;
    private SmoothMethod smoothMethod;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public BackgroundCorrectionFilter() {
        this(50, 0.1);
    }

    public BackgroundCorrectionFilter(final double radius, final double downSamplingFactor) {
        this(radius, downSamplingFactor, SmoothMethod.GAUSSIAN);
    }

    public BackgroundCorrectionFilter(final double radius, final double downSamplingFactor, final SmoothMethod smoothMethod) {
        this.radius = radius;
        this.downSamplingFactor = downSamplingFactor;
        this.smoothMethod = smoothMethod;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.radius = Filter.getDoubleParameter("radius", params);
        this.downSamplingFactor = Filter.getDoubleParameter("downSamplingFactor", params);
        this.smoothMethod = SmoothMethod.fromString(Filter.getStringParameter("smoothMethod", params));
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("radius", String.valueOf(radius));
        map.put("downSamplingFactor", String.valueOf(downSamplingFactor));
        map.put("smoothMethod", smoothMethod.name());
        return map;
    }

    @Override
    public void process(final ImageProcessor ip, final double scale) {
        final double shrinkFactor = downSamplingFactor / scale;

        // convert to 32-bit grayscale (float) for lossless processing
        final ImagePlus content = new ImagePlus("content", ip.convertToFloat());

        // resize to speed up processing
        final int targetWidth = (int) (shrinkFactor * ip.getWidth());
        final int targetHeight = (int) (shrinkFactor * ip.getHeight());
        final ImagePlus background = Scaler.resize(content, targetWidth, targetHeight, 1, "bilinear");

        // median filtering for actual background computation
        final double downscaledRadius = radius * shrinkFactor;
        final ImagePlus extendedBackground = extendBorder(background, downscaledRadius);
        smoothMethod.apply(extendedBackground.getProcessor(), downscaledRadius);
        final ImagePlus filteredBackground = crop(extendedBackground, downscaledRadius);

        // subtract mean to not shift the actual image values
        final double mean = ImageStatistics.getStatistics(filteredBackground.getProcessor(), Measurements.MEAN, null).mean;
        filteredBackground.getProcessor().subtract(mean);

        // finally, subtract the background
        final ImagePlus resizedBackground = Scaler.resize(filteredBackground, ip.getWidth(), ip.getHeight(), 1, "bilinear");
        ImageCalculator.run(content, resizedBackground, "subtract");

        // convert back to original bit depth
        ip.insert(content.getProcessor().convertToByteProcessor(), 0, 0);
    }

    private ImagePlus extendBorder(final ImagePlus input, final double padding) {
        final Img<FloatType> in = ImageJFunctions.wrap(input);
        final long extendSize = (long) Math.ceil(padding);
        final IntervalView<FloatType> view = Views.expandMirrorSingle(in, extendSize, extendSize);

        // make copy, otherwise the changes of the median filter are not visible
        // this leads to a very hard to find bug, so don't delete this copy!
        final ImagePlusImg<FloatType, FloatArray> test = ImagePlusImgs.floats(input.getWidth() + 2 * extendSize, input.getHeight() + 2 * extendSize);
        LoopBuilder.setImages(view, test).forEachPixel((v, t) -> t.set(v.get()));

        final ImagePlus out = test.getImagePlus();
        out.getProcessor().setMinAndMax(0.0, 255.0);
        return out;
    }

    private ImagePlus crop(final ImagePlus input, final double padding) {
        final long cropSize = (long) Math.ceil(padding);
        final long[] min = new long[] {cropSize, cropSize};
        final long[] max = new long[] {input.getWidth() - cropSize - 1, input.getHeight() - cropSize - 1};
        final Img<FloatType> in = ImageJFunctions.wrap(input);

        final RandomAccessibleInterval<FloatType> roi = Views.interval(in, min, max);
        final ImagePlus out = ImageJFunctions.wrap(roi, "cropped");
        out.getProcessor().setMinAndMax(0.0, 255.0);
        return out;
    }
}
