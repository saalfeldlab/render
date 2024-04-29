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
import org.janelia.alignment.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Streak corrector with a smooth parameterized mask that can also be used as {@link Filter}.
 * The mask is a smooth cutoff profile in the radial direction (given by angle)
 * multiplied by a small gaussian band in the orthogonal direction.
 * The tunable parameters are:
 * <ul>
 * <li>innerCutoff: inner radius of the cutoff profile in px; smaller values may improve de-streaking, values that are too small tinker with the overall intensity of the image</li>
 * <li>bandWidth: width of the gaussian band in px; larger values may improve de-streaking, values that are too high blur the image in the direction orthogonal to the angle</li>
 * <li>angle: angle of the band in deg; this should be orthogonal to the streaks (e.g., choose an angle of 0.0 for vertical streaks and 90.0 vor horizontal ones)</li>
 * </ul>
 *
 * @author Michael Innerberger
 */
public class SmoothMaskStreakCorrector
        extends StreakCorrector
        implements Filter {

    private int fftWidth = 0;
    private int fftHeight = 0;

    private int innerCutoff = 0;
    private int bandWidth = 0;
    private double angle = 0;

    private Img<FloatType> mask = null;


    public SmoothMaskStreakCorrector() {
        this(1);
    }

    public SmoothMaskStreakCorrector(final int numThreads) {
        super(numThreads);
    }

    public SmoothMaskStreakCorrector(final int numThreads,
                                     final int fftWidth,
                                     final int fftHeight,
                                     final int innerCutoff,
                                     final int bandWidth,
                                     final double angle) {
        super(numThreads);
        this.fftWidth = fftWidth;
        this.fftHeight = fftHeight;
        this.innerCutoff = innerCutoff;
        this.bandWidth = bandWidth;
        this.angle = angle;
    }

    public SmoothMaskStreakCorrector(final SmoothMaskStreakCorrector corrector) {
        super(corrector.getNumThreads());
        this.fftWidth = corrector.fftWidth;
        this.fftHeight = corrector.fftHeight;
        this.innerCutoff = corrector.innerCutoff;
        this.bandWidth = corrector.bandWidth;
        this.angle = corrector.angle;
        this.mask = corrector.mask;
    }

    public Img<FloatType> createMask(final Dimensions dim) {
        ensureDimensions(dim, fftWidth, fftHeight);

        if (fftWidth == 0 || fftHeight == 0) {
            throw new IllegalStateException("Attempted to create mask before initialization of parameters.");
        }

        // cache mask since only one size is supported anyway
        if (mask == null) {
            LOG.info("Creating mask with parameters: fftWidth={}, fftHeight={}, innerCutoff={}, bandWidth={}, angle={}",
                     fftWidth, fftHeight, innerCutoff, bandWidth, angle);

            mask = ArrayImgs.floats(dim.dimensionsAsLongArray());

            for (final FloatType t : mask) {
                t.setOne();
            }

            // get coordinates in [-fftWidth,0]x[-fftHeight/2,fftHeight/2] beginning from lower left corner
            final double[] xCoords = IntStream.range(0, fftWidth)
                    .mapToDouble(x -> (double) x - fftWidth + 1).toArray();
            final double[] yCoords = IntStream.range(0, fftHeight)
                    .mapToDouble(y -> y - ((double) fftHeight - 1) / 2).toArray();

            final RandomAccess<FloatType> ra = mask.randomAccess();
            final double innerSigma = 2 * innerCutoff * innerCutoff;
            final double bandSigma = 2 * bandWidth * bandWidth;
            final double rad = Math.toRadians(angle);
            final double s = Math.sin(rad);
            final double c = Math.cos(rad);

            // the mask has a smooth cutoff profile in the radial direction (given by angle)
            // multiplied by a small gaussian band in the orthogonal direction
            for (int y = 0; y < fftHeight; y++) {
                for (int x = 0; x < fftWidth; x++) {
                    final double newX = xCoords[x] * c - yCoords[y] * s;
                    final double newY = xCoords[x] * s + yCoords[y] * c;
                    final double newX2 = newX * newX;
                    final double newY2 = newY * newY;
                    final float cutoff = (float) (1 - Math.exp(-newX2 / innerSigma));
                    final float band = (float) Math.exp(-newY2 / bandSigma);

                    ra.setPosition(x, 0);
                    ra.setPosition(y, 1);
                    ra.get().set(1 - cutoff * band);
                }
            }
        }

        return mask;
    }

    @Override
    public void init(final Map<String, String> params) {
        final String[] values = Filter.getCommaSeparatedStringParameter(DATA_STRING_NAME, params);

        if (values.length != 5) {
            throw new IllegalArgumentException(DATA_STRING_NAME + " must have pattern <fftWidth>,<fftHeight>,<innerCutoff>,<bandWidth>,<angle>");
        }

        this.fftWidth = Integer.parseInt(values[0]);
        this.fftHeight = Integer.parseInt(values[1]);
        this.innerCutoff = Integer.parseInt(values[2]);
        this.bandWidth = Integer.parseInt(values[3]);
        this.angle = Double.parseDouble(values[4]);
        this.mask = null;
    }

    public String toDataString() {
		return String.valueOf(fftWidth) + ',' +
				fftHeight + ',' +
				innerCutoff + ',' +
				bandWidth + ',' +
				angle;
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

        final ImagePlus floatIP = new ImagePlus("input", ip.convertToFloat());
        final Img<FloatType> img = ImageJFunctions.wrapFloat(floatIP);
        checkWrappingSucceeded(img, ip, FloatType.class);

        // remove streaking
        final Img<FloatType> imgCorr = fftBandpassCorrection(img, false);

        // convert to 8-bit grayscale
        final RandomAccessibleInterval<UnsignedByteType> fixed =
                Converters.convertRAI(imgCorr,
                                      (i,o) -> o.set(Math.max(0, Math.min(255, Math.round(i.get())))),
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
