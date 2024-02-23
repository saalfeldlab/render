package org.janelia.alignment.destreak;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import org.apache.commons.lang.math.DoubleRange;
import org.apache.commons.lang.math.IntRange;
import org.janelia.alignment.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Streak corrector with a configurable/parameterized mask that can also be used as {@link Filter}
 * (derived from original code to correct Z07422_17_VNC_1 volume).
 *
 * @author Stephan Preibisch
 * @author Eric Trautman
 */
public class SmoothMaskStreakCorrector
        extends StreakCorrector
        implements Filter {

    private int fftWidth;
    private int fftHeight;
    private int extraX;
    private int extraY;
    
    private int innerRadius;
    private int outerRadius;
    private int bandWidth;
    private double angle;

    private int[][] regionsToClear;

    public SmoothMaskStreakCorrector() {
        this(1);
    }

    public SmoothMaskStreakCorrector(final int numThreads) {
        super(numThreads);
    }

    public SmoothMaskStreakCorrector(final int numThreads,
                                     final int fftWidth,
                                     final int fftHeight,
                                     final int innerRadius,
                                     final int outerRadius,
                                     final int bandWidth,
                                     final double angle) {
        super(numThreads);
        this.fftWidth = fftWidth;
        this.fftHeight = fftHeight;
        this.innerRadius = innerRadius;
        this.outerRadius = outerRadius;
        this.bandWidth = bandWidth;
        this.angle = angle;
    }

    public SmoothMaskStreakCorrector(final SmoothMaskStreakCorrector corrector) {
        super(corrector.getNumThreads());
        this.fftWidth = corrector.fftWidth;
        this.fftHeight = corrector.fftHeight;
        this.innerRadius = corrector.innerRadius;
        this.outerRadius = corrector.outerRadius;
        this.bandWidth = corrector.bandWidth;
        this.angle = corrector.angle;
    }

    public Img<FloatType> createMask(final Dimensions dim) {
        ensureDimensions(dim, fftWidth, fftHeight);

        final Img<FloatType> mask = ArrayImgs.floats(dim.dimensionsAsLongArray());

        for (final FloatType t : mask) {
            t.setOne();
        }

        // get coordinates in [-fftWidth,0]x[-fftHeight/2,fftHeight/2] beginning from lower left corner
        final double[] xCoords = IntStream.range(0, fftWidth)
                .mapToDouble(x -> (double) x - fftWidth + 1).toArray();
        final double[] yCoords = IntStream.range(0, fftHeight)
                .mapToDouble(y -> y - ((double) fftHeight - 1) / 2).toArray();

        final RandomAccess<FloatType> ra = mask.randomAccess();
        final double outerSigma = 2 * outerRadius * outerRadius;
        final double innerSigma = 2 * innerRadius * innerRadius;
        final double bandSigma = 2 * bandWidth * bandWidth;
        final double rad = Math.toRadians(angle);
        final double s = Math.sin(rad);
        final double c = Math.cos(rad);

        // smooth with a difference of gaussian (dog) profile in the radial direction (given by angle)
        // multiplied by a small gaussian band in the orthogonal direction
        for (int y = 0; y < fftHeight; y++) {
            for (int x = 0; x < fftWidth; x++) {
                final double newX = xCoords[x] * c - yCoords[y] * s;
                final double newY = xCoords[x] * s + yCoords[y] * c;
                final double newX2 = newX * newX;
                final double newY2 = newY * newY;
                final float dog = (float) (Math.exp(-newX2 / outerSigma) - Math.exp(-newX2 / innerSigma));
                final float band = (float) Math.exp(-newY2 / bandSigma);

                ra.setPosition(x, 0);
                ra.setPosition(y, 1);
                ra.get().set(1 - dog * band);
            }
        }

        return mask;
    }

    @Override
    public void init(final Map<String, String> params) {
        final String[] values = Filter.getCommaSeparatedStringParameter(DATA_STRING_NAME, params);

        final int numberOfRegions = (values.length - 4) / 4;
        if ((numberOfRegions < 1) || (values.length % 4 != 0)) {
            throw new IllegalArgumentException(DATA_STRING_NAME +
                                               " must have pattern <fftWidth>,<fftHeight>,<extraX>,<extraY>,<regionX>,<regionY>,<regionW>,<regionH>,[additional clear regions]...");
        }

        this.fftWidth = Integer.parseInt(values[0]);
        this.fftHeight = Integer.parseInt(values[1]);
        this.extraX = Integer.parseInt(values[2]);
        this.extraY = Integer.parseInt(values[3]);

        this.regionsToClear = new int[numberOfRegions][4];
        int regionIndex = 0;
        for (int i = 4; i < values.length; i+=4) {
            for (int j = 0; j < 4; j++) {
                this.regionsToClear[regionIndex][j] = Integer.parseInt(values[i+j]);
            }
            regionIndex++;
        }
    }

    public String toDataString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(fftWidth).append(',');
        sb.append(fftHeight).append(',');
        sb.append(extraX).append(',');
        sb.append(extraY);
        for (final int[] region : regionsToClear) {
            for (final int value : region) {
                sb.append(',').append(value);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return this.toDataString();
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new HashMap<>();
        map.put(DATA_STRING_NAME, this.toDataString());
        return map;
    }

    @Override
    public void process(final ImageProcessor ip, final double scale) {
        // TODO: check with @StephanPreibisch to see if it makes sense to scale clear regions
        if (scale != 1.0) {
            throw new UnsupportedOperationException("this filter only supports full scale images");
        }

        final ImagePlus imp = new ImagePlus("input", ip.convertToFloat());
        final Img<FloatType> img = ImageJFunctions.wrapFloat(imp);
        if (img == null) {
            throw new IllegalArgumentException("failed to wrap " + ip.getClass().getName() +
                                               " as Img<UnsignedByteType>");
        }

        final double avg = StreakCorrector.avgIntensity(img);
        LOG.debug("process: average intensity is {}", avg);

        // remove streaking (but it'll introduce a wave pattern)
        final Img<FloatType> imgCorr = fftBandpassCorrection(img, true);

        // create the wave pattern introduced by the filtering above
        final Img<FloatType> patternCorr = createPattern(imgCorr.dimensionsAsLongArray(), avg);

        // removes the wave pattern from the corrected image
        final RandomAccessibleInterval<UnsignedByteType> fixed =
                Converters.convertRAI(imgCorr,
                                      patternCorr,
                                      (i1,i2,o) ->
                                              o.set(Math.max(0,
                                                             Math.min( 255, Math.round( i1.get() - i2.get() ) ) ) ),
                                      new UnsignedByteType());

        // TODO: check with @StephanPreibisch to see if there is a better way to copy fixedIp to input
        final ImagePlus fixedImp = ImageJFunctions.wrap(fixed, "fixed");
        final ImageProcessor fixedIp = fixedImp.getProcessor().convertToByteProcessor();
        for (int i = 0; i < ip.getPixelCount(); i++) {
            ip.set(i, fixedIp.get(i));
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SmoothMaskStreakCorrector.class);
}
