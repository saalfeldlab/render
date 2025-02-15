package org.janelia.alignment.intensity;

import net.imglib2.Dimensions;
import net.imglib2.blocks.TempArray;
import net.imglib2.util.Cast;

import java.util.Arrays;

import static net.imglib2.type.PrimitiveType.FLOAT;

/**
 * Apply scaling and translation to {@code Coefficients} array.
 * Use {@link #line} to extract an X line segment of transformed interpolated coefficients.
 */
class TransformCoefficients {

    private final Coefficients coefficients;
    private final int n;
    private final double[] g;
    private final double[] h;
    private final float[] sof;
    private final int[] S;
    private final TempArray<float[]>[] tempArrays;

    static TransformCoefficients create(final Dimensions target, final Coefficients coefficients) {
        final int n = coefficients.numDimensions();

        final double[] scale = new double[n];
        Arrays.setAll(scale, d -> target.dimension(d) / coefficients.size(d));
        // TODO: probably a bug!? integer division in floating point context...
        //       It should be this instead:
        //           Arrays.setAll(scale, d -> (double) target.dimension(d) / coefficients.size(d));
        //       but we keep the bug for compatibility for now

        // shift everything in xy by 0.5 pixels so the coefficient sits in the middle of the block
        final double[] translation = new double[n];
        Arrays.setAll(translation, d -> 0.5 * scale[d]);

        return new TransformCoefficients(scale, translation, coefficients);
    }

    TransformCoefficients(final double[] scale, final double[] translation, final Coefficients coefficients) {
        this.coefficients = coefficients;
        n = coefficients.numDimensions();
        g = new double[n];
        h = new double[n];
        sof = new float[n];
        S = new int[n];
        Arrays.setAll(g, d -> 1.0 / scale[d]);
        Arrays.setAll(h, d -> -translation[d] * g[d]);
        tempArrays = Cast.unchecked(new TempArray[n]);
        Arrays.setAll(tempArrays, i -> TempArray.forPrimitiveType(FLOAT));
    }

    private TransformCoefficients(TransformCoefficients t) {
        this.coefficients = t.coefficients;
        this.n = t.n;
        this.g = t.g;
        this.h = t.h;
        this.sof = new float[n];
        this.S = new int[n];
        tempArrays = Cast.unchecked(new TempArray[n]);
        Arrays.setAll(tempArrays, i -> TempArray.forPrimitiveType(FLOAT));
    }

    void line(final long[] start, final int len0, final int coeff_index, final float[] target) {

        final float[] coeff = coefficients.flattenedCoefficients[coeff_index];

        for (int d = 0; d < n; ++d) {
            sof[d] = (float) (g[d] * start[d] + h[d]);
            S[d] = (int) Math.floor(sof[d]);
        }
        final int Smax = (int) Math.floor(len0 * g[0] + sof[0]) + 1;
        final int L0 = Smax - S[0] + 1;

        // interpolate all dimensions > 0 into tmp array
        final float[] tmp = tempArrays[0].get(L0);
        int o = 0;
        for (int d = 1; d < n; ++d) {
            final int posd = Math.min(Math.max(S[d], 0), coefficients.size(d) - 1);
            o += coefficients.stride(d) * posd;
        }
        interpolate_coeff_line(1, L0, coeff, tmp, o);

        // interpolate in dim0 into target array
        float s0f = sof[0] - S[0];
        final float step = (float) g[0];
        for (int x = 0; x < len0; ++x) {
            final int s0 = (int) s0f;
            final float r0 = s0f - s0;
            final float a0 = tmp[s0];
            final float a1 = tmp[s0 + 1];
            target[x] = a0 + r0 * (a1 - a0);
            s0f += step;
        }
    }

    private void interpolate_coeff_line(final int d, final int L0, final float[] coeff, final float[] dest, final int o) {
        if (d < n) {
            interpolate_coeff_line(d + 1, L0, coeff, dest, o);
            if (S[d] >= 0 && S[d] <= coefficients.size(d) - 2) {
                final float[] tmp = tempArrays[d].get(L0);
                interpolate_coeff_line(d + 1, L0, coeff, tmp, o + coefficients.stride(d));
                final float r = sof[d] - S[d];
                interpolate(dest, tmp, r, L0);
            }
        } else {
            padded_coeff_line(S[0], L0, coeff, dest, o);
        }
    }

    private void padded_coeff_line(final int x0, final int len, final float[] coeff, final float[] dest, final int o) {
        final int w = coefficients.size(0);
        final int pad_left = Math.max(0, Math.min(len, -x0));
        final int pad_right = Math.max(0, Math.min(len, x0 + len - w));
        final int copy_len = len - pad_left - pad_right;

        if (pad_left > 0) {
            Arrays.fill(dest, 0, pad_left, coeff[o]);
        }
        if (copy_len > 0) {
            System.arraycopy(coeff, o + x0 + pad_left, dest, pad_left, copy_len);
        }
        if (pad_right > 0) {
            Arrays.fill(dest, len - pad_right, len, coeff[w - 1]);
        }
    }

    // elements a[i] are set to (1-r) * a[i] + r * b[i]
    private void interpolate(final float[] a, final float[] b, final float r, final int len) {
        for (int i = 0; i < len; ++i) {
            a[i] += r * (b[i] - a[i]);
        }
    }

    TransformCoefficients independentCopy() {
        return new TransformCoefficients(this);
    }

    int numDimensions() {
        return n;
    }
}
