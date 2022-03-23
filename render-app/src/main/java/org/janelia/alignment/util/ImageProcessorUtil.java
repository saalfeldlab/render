package org.janelia.alignment.util;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Utility methods for working with images.
 *
 * @author Stephan Preibisch
 */
public class ImageProcessorUtil {

    /**
     * @return true if the specified pixel is masked out; otherwise false.
     */
    public static boolean isMaskedOut(final int x,
                                      final int y,
                                      final ImageProcessor maskProcessor) {
        return maskProcessor.get(x, y) == 0;
    }

    /**
     * @return true if the specified pixel or one near it is masked out; otherwise false.
     */
    public static boolean isNearMaskedOut(final int x,
                                          final int y,
                                          final int distance,
                                          final ImageProcessor maskProcessor) {
        boolean isNearMask = false;

        final int minX = Math.max(0, x - distance);
        final int maxX = Math.min(maskProcessor.getWidth(), x + distance + 1);
        final int minY = Math.max(0, y - distance);
        final int maxY = Math.min(maskProcessor.getHeight(), y + distance + 1);

        for (int nearbyX = minX; nearbyX < maxX; nearbyX++) {
            for (int nearbyY = minY; nearbyY < maxY; nearbyY++) {
                if (isMaskedOut(nearbyX, nearbyY, maskProcessor)) {
                    isNearMask = true;
                    break;
                }
            }
        }
        return isNearMask;
    }

    /**
     * @return number of kilobytes consumed by the specified processor's pixels.
     */
    public static long getKilobytes(final ImageProcessor imageProcessor) {
        final long bitCount = ((long) imageProcessor.getPixelCount()) * imageProcessor.getBitDepth();
        return bitCount / 8000L;
    }

    /**
     * Lou Scheffer's 16-bit to 8-bit "compression" algorithm copied from
     * /groups/flyem/home/flyem/bin/compress_dats/build2/Compress.cpp .
     *
     * This process was used to convert 16-bit FIB-SEM .dat images to 8-bit .png images for Fly EM volumes.
     *
     * The code has been kept as close as possible to the cpp code (including comments and print statements)
     * to make it easier to confirm it is the same.
     */
    public static ByteProcessor compressCompute(final ShortProcessor imageProcessor) {

        // First, find the mean and standard deviation of the 'real' non-saturated pixels.
        // Ignore any that are too close to saturated light or dark.
        final short SAT = 3; // how close to the boundary do you need to be to be considered 'saturated'
        final short minIntensity = -32768 + SAT;
        final short maxIntensity = 32767 - SAT;

        final short[] pixelArray = (short[]) imageProcessor.getPixels();
        long sum = 0;
        int unsaturatedCount = 0;

        for (final short pix : pixelArray) {
            if ((pix >= minIntensity) && (pix <= maxIntensity)) {
                sum += pix;
                unsaturatedCount++;
            }
        }

        final double unsaturatedPct = (unsaturatedCount * 100.0) / pixelArray.length;
        System.out.printf("%d real pixels, %f percent\n", unsaturatedCount, unsaturatedPct);

        final double mean = (double) sum / unsaturatedCount;

        long variance = 0;
        for (final short pix : pixelArray) {
            if ((pix >= minIntensity) && (pix <= maxIntensity)) {
                variance += Math.pow((pix - mean), 2);
            }
        }
        final double varianceMean = (double) variance / (unsaturatedCount - 1);
        final double stdDev = Math.sqrt(varianceMean);
        System.out.printf("Of the above image points, mean= %f and std dev = %f\n", mean, stdDev);

        // Convert mean-4*sigma -> 0, mean +4 sigma to 255.
        double low = mean - (4 * stdDev);
        double high = mean + (4 * stdDev);

        if (low < -32768) {
            System.out.println("minus 4 sigma < -32768.  Changing to 0");
            low = -32768;
        }
        if (high > 32767.0) {
            System.out.println(" plus 4 sigma > 32767.  Changing to 65535");
            high = 32767.0;
        }
        final double temp = low; low = high; high = temp;  // swap low and high

        final double span = high - low;
        System.out.printf("Compress:  low %.2f  -> 0, high %.2f -> 255\n", low, high);

        // TODO: see if dat header QuantLow and QuantHigh values could be used instead since they seem to be
        //       the same as the swapped low and high values here (would allow us to skip stdDev calculation)

        final int pixelCount = imageProcessor.getPixelCount();
        final ByteProcessor byteProcessor = new ByteProcessor(imageProcessor.getWidth(), imageProcessor.getHeight());

        int tooLow = 0;
        int tooHigh = 0;
        int pix;
        for (int j = 0; j < pixelCount; j++) {
            pix = (int) ((255.0 * (((short) imageProcessor.get(j) - low) / span)) + 0.5);
            if (pix < 0) {
                pix = 0;
                tooLow++;
            } else if (pix > 255) {
                pix = 255;
                tooHigh++;
            }
            byteProcessor.set(j, pix);
        }

        final double tooLowPct = ((double) tooLow / pixelCount) * 100.0;
        final double tooHighPct = ((double) tooHigh / pixelCount) * 100.0;
        System.out.printf("%d (%.5f%%) clipped to black, %d (%.5f%%) clipped to white\n",
                          tooLow, tooLowPct, tooHigh, tooHighPct);

        return byteProcessor;
    }
}
