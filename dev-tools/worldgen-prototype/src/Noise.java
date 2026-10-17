import java.util.SplittableRandom;

/** Seeded 2D simplex noise (Gustavson's formulation) with fBm and ridged fBm. Pure Java. */
final class Noise {
    private static final float F2 = 0.36602540378f, G2 = 0.2113248654f;
    private static final float[] GX = {1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0};
    private static final float[] GY = {1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1};
    private final int[] perm = new int[512];

    Noise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        SplittableRandom r = new SplittableRandom(seed);
        for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int t = p[i]; p[i] = p[j]; p[j] = t; }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    /** Simplex noise in about [-1, 1]. */
    float noise(float x, float y) {
        float s = (x + y) * F2;
        int i = fastFloor(x + s), j = fastFloor(y + s);
        float t = (i + j) * G2, x0 = x - (i - t), y0 = y - (j - t);
        int i1 = x0 > y0 ? 1 : 0, j1 = 1 - i1;
        float x1 = x0 - i1 + G2, y1 = y0 - j1 + G2, x2 = x0 - 1 + 2 * G2, y2 = y0 - 1 + 2 * G2;
        int ii = i & 255, jj = j & 255;
        float n = 0;
        float t0 = 0.5f - x0 * x0 - y0 * y0;
        if (t0 > 0) { int g = perm[ii + perm[jj]] % 12; t0 *= t0; n += t0 * t0 * (GX[g] * x0 + GY[g] * y0); }
        float t1 = 0.5f - x1 * x1 - y1 * y1;
        if (t1 > 0) { int g = perm[ii + i1 + perm[jj + j1]] % 12; t1 *= t1; n += t1 * t1 * (GX[g] * x1 + GY[g] * y1); }
        float t2 = 0.5f - x2 * x2 - y2 * y2;
        if (t2 > 0) { int g = perm[ii + 1 + perm[jj + 1]] % 12; t2 *= t2; n += t2 * t2 * (GX[g] * x2 + GY[g] * y2); }
        return 70f * n;
    }

    /** Fractal sum, normalised to about [-1, 1]. Lower gain = smoother result. */
    float fbm(float x, float y, int oct, float gain) {
        float s = 0, a = 1, n = 0, f = 1;
        for (int o = 0; o < oct; o++) { s += a * noise(x * f + o * 19.1f, y * f - o * 7.7f); n += a; a *= gain; f *= 2.02f; }
        return s / n;
    }

    /** Ridged fractal in [0, 1]: 1 along the zero-lines of the base noise (crests), detail weighted by lower octaves. */
    float ridged(float x, float y, int oct, float gain) {
        float s = 0, a = 1, n = 0, f = 1, w = 1;
        for (int o = 0; o < oct; o++) {
            float v = 1 - Math.abs(noise(x * f + o * 31.3f, y * f + o * 3.9f));
            v *= v; s += a * v * w; n += a; w = Math.min(1f, v * 1.5f); a *= gain; f *= 2.02f;
        }
        return s / n;
    }

    static int fastFloor(float v) { int i = (int) v; return v < i ? i - 1 : i; }
}
