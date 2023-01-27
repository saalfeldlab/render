package org.janelia.alignment.destreak;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.HashMap;
import java.util.Map;

import org.janelia.alignment.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Streak corrector with a configurable/parameterized mask that can also be used as {@link Filter}
 * (derived from original code to correct Z07422_17_VNC_1 volume).
 *
 * @author Stephan Preibisch
 * @author Eric Trautman
 */
public class ConfigurableMaskStreakCorrector
        extends StreakCorrector
        implements Filter {

    private int fftWidth;
    private int fftHeight;
    private int extraX;
    private int extraY;

    private int[][] regionsToClear;

    public ConfigurableMaskStreakCorrector() {
        this(1);
    }

    public ConfigurableMaskStreakCorrector(final int numThreads) {
        super(numThreads);
    }

    public ConfigurableMaskStreakCorrector(final int numThreads,
                                           final int fftWidth,
                                           final int fftHeight,
                                           final int extraX,
                                           final int extraY,
                                           final int[][] regionsToClear) {
        super(numThreads);
        this.fftWidth = fftWidth;
        this.fftHeight = fftHeight;
        this.extraX = extraX;
        this.extraY = extraY;
        this.regionsToClear = regionsToClear;
    }

    public Img<FloatType> createMask(final Dimensions dim) {

        if (dim.dimension(0) != fftWidth || dim.dimension( 1) != fftHeight) {
            throw new IllegalArgumentException("mask is hard-coded for an FFT size of " + fftWidth + "x" + fftHeight);
        }

        final ArrayImg<FloatType, FloatArray> mask = ArrayImgs.floats(dim.dimensionsAsLongArray());

        for (final FloatType t : mask) {
            t.setOne();
        }

        for (final int[] region : regionsToClear) {
            clear(mask, region[0], region[1], region[2], region[3], extraX, extraY);
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
    public ImageProcessor process(final ImageProcessor ip,
                                  final double scale) {
        // TODO: check with @StephanPreibisch to see if it makes sense to scale clear regions
        if (scale != 1.0) {
            throw new UnsupportedOperationException("this filter only supports full scale images");
        }

        final ImagePlus imp = new ImagePlus("input", ip);
        final Img<UnsignedByteType> img = ImageJFunctions.wrapByte(imp);

        final double avg = StreakCorrector.avgIntensity(img);
        LOG.debug("process: average intensity is {}", avg);

        final Img<UnsignedByteType> imgCorr = fftBandpassCorrection( img );
        final Img<FloatType> patternCorr = createPattern(imgCorr.dimensionsAsLongArray(), avg);
        final RandomAccessibleInterval<UnsignedByteType> fixed =
                Converters.convertRAI(imgCorr,
                                      patternCorr,
                                      (i1,i2,o) ->
                                              o.set(Math.max(0,
                                                             Math.min( 255, Math.round( i1.get() - i2.get() ) ) ) ),
                                      new UnsignedByteType());

        final ImagePlus fixedImp = ImageJFunctions.wrap(fixed, "fixed");
        return fixedImp.getProcessor();
    }

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurableMaskStreakCorrector.class);
}
