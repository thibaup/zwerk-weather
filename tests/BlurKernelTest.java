package com.zwerk.weather;

import java.util.Arrays;

/** Image properties that matter when a translucent backdrop is blurred. */
public final class BlurKernelTest {
    private static int checks;

    public static void main(String[] args) {
        int[] constant = new int[23 * 17];
        Arrays.fill(constant, 0xff428ac3);
        BlurKernel.blur(constant, new int[constant.length], 23, 17, 6f, 3f);
        check(allEqual(constant, 0xff428ac3), "solid colours stay solid, including clamped edges");

        int[] identity = {0x00ff0000, 0x01801040, 0x80ff2040, 0xff234567};
        int[] original = identity.clone();
        BlurKernel.blur(identity, new int[4], 2, 2, 0f, 0f);
        check(Arrays.equals(identity, original), "zero radius preserves every pixel exactly");

        int[] single = {0x80ff0000};
        BlurKernel.blur(single, new int[1], 1, 1, 50f, 40f);
        check(single[0] == 0x80ff0000, "radii larger than the image clamp safely");

        int size = 31;
        int middle = size / 2;
        int[] impulse = new int[size * size];
        Arrays.fill(impulse, 0xff000000);
        impulse[middle * size + middle] = 0xffffffff;
        BlurKernel.blur(impulse, new int[impulse.length], size, size, 2f, 2f);
        int center = impulse[middle * size + middle] & 255;
        check(center > 0 && center < 255, "blur softens a point instead of only resizing it");
        check((impulse[middle * size + middle + 2] & 255) > 0, "blur spreads into neighbouring pixels");
        boolean symmetric = true;
        boolean opaque = true;
        int diagonalRounding = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int colour = impulse[y * size + x];
                symmetric &= colour == impulse[y * size + size - 1 - x]
                        && colour == impulse[(size - 1 - y) * size + x];
                diagonalRounding = Math.max(diagonalRounding,
                        Math.abs((colour & 255) - (impulse[x * size + y] & 255)));
                opaque &= colour >>> 24 == 255;
            }
        }
        check(symmetric, "a circular point remains symmetric in both directions");
        check(diagonalRounding <= 1, "axis order differs only by one channel rounding step: " + diagonalRounding);
        check(opaque, "an opaque map does not acquire transparent edges");

        int width = 21;
        int[] horizontal = new int[width * 3];
        Arrays.fill(horizontal, 0xff000000);
        horizontal[width + 10] = 0xffffffff;
        BlurKernel.blur(horizontal, new int[horizontal.length], width, 3, 2f, 0f);
        check((horizontal[width + 9] & 255) > 0, "directional blur spreads along its enabled axis");
        check(horizontal[10] == 0xff000000 && horizontal[2 * width + 10] == 0xff000000,
                "directional blur leaves the disabled axis unchanged");

        int[] translucent = new int[21];
        Arrays.fill(translucent, 0x00ff0000);
        translucent[10] = 0xff0000ff;
        BlurKernel.blur(translucent, new int[21], 21, 1, 2f, 0f);
        boolean blueOnly = true;
        boolean cleanTransparent = true;
        for (int colour : translucent) {
            if (colour >>> 24 == 0) cleanTransparent &= colour == 0;
            else blueOnly &= (colour & 0x00ffffff) == 0x000000ff;
        }
        check(blueOnly, "hidden RGB in transparent pixels cannot create red fringes");
        check(cleanTransparent, "fully transparent output has no hidden colour residue");
        check((translucent[10] >>> 24) > 0 && (translucent[10] >>> 24) < 255
                        && (translucent[9] >>> 24) > 0,
                "alpha spreads together with premultiplied colour");

        int[] scratch = new int[original.length];
        int[] first = original.clone();
        int[] second = original.clone();
        Arrays.fill(scratch, 0xabcdef01);
        BlurKernel.blur(first, scratch, 2, 2, 1f, 1f);
        Arrays.fill(scratch, 0x12345678);
        BlurKernel.blur(second, scratch, 2, 2, 1f, 1f);
        check(Arrays.equals(first, second), "reused scratch buffers never leak a previous frame");

        invalid(() -> BlurKernel.blur(new int[1], new int[1], 0, 1, 1f, 1f), "zero dimensions");
        invalid(() -> BlurKernel.blur(new int[1], new int[1], 65536, 65536, 1f, 1f),
                "overflowing dimensions");
        invalid(() -> BlurKernel.blur(new int[3], new int[4], 2, 2, 1f, 1f), "short input buffer");
        invalid(() -> BlurKernel.blur(new int[4], new int[3], 2, 2, 1f, 1f), "short scratch buffer");
        int[] shared = new int[4];
        invalid(() -> BlurKernel.blur(shared, shared, 2, 2, 1f, 1f), "aliased buffers");
        invalid(() -> BlurKernel.blur(new int[1], new int[1], 1, 1, -1f, 1f), "negative radius");
        invalid(() -> BlurKernel.blur(new int[1], new int[1], 1, 1, 1f, Float.NaN), "NaN radius");
        invalid(() -> BlurKernel.blur(new int[1], new int[1], 1, 1, Float.POSITIVE_INFINITY, 1f),
                "infinite radius");
        System.out.println(checks + " backdrop blur checks passed");
    }

    private static boolean allEqual(int[] pixels, int colour) {
        for (int pixel : pixels) if (pixel != colour) return false;
        return true;
    }

    private static void invalid(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + " was accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
