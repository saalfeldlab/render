package org.janelia.alignment.filter;

import ij.ImagePlus;
import ij.measure.Measurements;
import ij.plugin.ImageCalculator;
import ij.plugin.Scaler;
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

public class BackgroundCorrectionFilter implements Filter {

    private double radius;
    private final double downSamplingFactor;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public BackgroundCorrectionFilter() {
        this(50, 0.1);
    }

    public BackgroundCorrectionFilter(final double radius, final double downSamplingFactor) {
        this.radius = radius;
        this.downSamplingFactor = downSamplingFactor;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.radius = Filter.getDoubleParameter("radius", params);
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("radius", String.valueOf(radius));
        return map;
    }

    @Override
    public void process(final ImageProcessor ip, final double scale) {
        final double shrinkFactor = downSamplingFactor * scale;

        // convert to 32-bit grayscale (float) for lossless processing
        final ImagePlus content = new ImagePlus("content", ip.convertToFloat());

        // resize to speed up processing
        final int targetWidth = (int) (shrinkFactor * ip.getWidth());
        final int targetHeight = (int) (shrinkFactor * ip.getHeight());
        final ImagePlus background = Scaler.resize(content, targetWidth, targetHeight, 1, "bilinear");

        // median filtering for actual background computation
        final double downscaledRadius = radius * shrinkFactor;
        final RankFilters rankFilters = new RankFilters();
        final ImagePlus extendedBackground = extendBorder(background, downscaledRadius);
        rankFilters.rank(extendedBackground.getProcessor(), downscaledRadius, RankFilters.MEDIAN);
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
