package org.janelia.alignment.transform;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.apache.commons.codec.binary.Base64;

/**
 * Utility to encode/decode double arrays copied from {@link mpicbg.trakem2.transform.ThinPlateSplineTransform}.
 *
 * @author Stephan Saalfeld
 */
public class DoubleArrayConverter {

    // TODO: repackage and reuse in ThinPlateSplineTransform

    public static String encodeBase64(final double[] src) {
        final byte[] bytes = new byte[src.length * 8];
        for (int i = 0, j = -1; i < src.length; ++i) {
            final long bits = Double.doubleToLongBits(src[i]);
            bytes[++j] = (byte) (bits >> 56);
            bytes[++j] = (byte) ((bits >> 48) & 0xffL);
            bytes[++j] = (byte) ((bits >> 40) & 0xffL);
            bytes[++j] = (byte) ((bits >> 32) & 0xffL);
            bytes[++j] = (byte) ((bits >> 24) & 0xffL);
            bytes[++j] = (byte) ((bits >> 16) & 0xffL);
            bytes[++j] = (byte) ((bits >> 8) & 0xffL);
            bytes[++j] = (byte) (bits & 0xffL);
        }
        final Deflater deflater = new Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        final byte[] zipped = new byte[bytes.length];
        final int n = deflater.deflate(zipped);
        if (n == bytes.length)
            return '@' + Base64.encodeBase64String(bytes);
        else
            return Base64.encodeBase64String(Arrays.copyOf(zipped, n));
    }

    public static double[] decodeBase64(final String src,
                                        final int n)
            throws DataFormatException {

        final byte[] bytes;
        if (src.charAt(0) == '@') {
            bytes = Base64.decodeBase64(src.substring(1));
        } else {
            bytes = new byte[n * 8];
            final byte[] zipped = Base64.decodeBase64(src);
            final Inflater inflater = new Inflater();
            inflater.setInput(zipped, 0, zipped.length);

            inflater.inflate(bytes);
            inflater.end();
        }
        final double[] doubles = new double[n];
        for (int i = 0, j = -1; i < n; ++i) {
            long bits = 0L;
            bits |= (bytes[++j] & 0xffL) << 56;
            bits |= (bytes[++j] & 0xffL) << 48;
            bits |= (bytes[++j] & 0xffL) << 40;
            bits |= (bytes[++j] & 0xffL) << 32;
            bits |= (bytes[++j] & 0xffL) << 24;
            bits |= (bytes[++j] & 0xffL) << 16;
            bits |= (bytes[++j] & 0xffL) << 8;
            bits |= bytes[++j] & 0xffL;
            doubles[i] = Double.longBitsToDouble(bits);
        }
        return doubles;
    }
}
