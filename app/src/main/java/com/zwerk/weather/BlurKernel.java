package com.zwerk.weather;

/** Three separable box passes approximate a Gaussian in linear time, with clamped edges. */
final class BlurKernel {
    private BlurKernel() { }

    static void blur(int[] pixels, int[] scratch, int width, int height, float sigmaX, float sigmaY) {
        long count = (long) width * height;
        if (width <= 0 || height <= 0 || pixels.length < count
                || scratch.length < count || pixels == scratch) {
            throw new IllegalArgumentException("Invalid blur buffers");
        }
        int[] horizontal = radii(sigmaX);
        int[] vertical = radii(sigmaY);
        if (sigmaX == 0f && sigmaY == 0f) return;
        boolean opaque = true;
        for (int i = 0; i < width * height; i++) {
            int color = pixels[i];
            int alpha = color >>> 24;
            if (alpha == 255) continue;
            opaque = false;
            pixels[i] = alpha << 24 | ((color >>> 16 & 255) * alpha + 127) / 255 << 16
                    | ((color >>> 8 & 255) * alpha + 127) / 255 << 8
                    | ((color & 255) * alpha + 127) / 255;
        }
        for (int pass = 0; pass < 3; pass++) {
            sweep(pixels, scratch, width, height, horizontal[pass], true);
            sweep(scratch, pixels, width, height, vertical[pass], false);
        }
        if (!opaque) {
            for (int i = 0; i < width * height; i++) {
                int color = pixels[i];
                int alpha = color >>> 24;
                pixels[i] = alpha == 0 ? 0 : alpha << 24
                        | Math.min(255, ((color >>> 16 & 255) * 255 + alpha / 2) / alpha) << 16
                        | Math.min(255, ((color >>> 8 & 255) * 255 + alpha / 2) / alpha) << 8
                        | Math.min(255, ((color & 255) * 255 + alpha / 2) / alpha);
            }
        }
    }

    private static int[] radii(float sigma) {
        if (!Float.isFinite(sigma) || sigma < 0f) throw new IllegalArgumentException("Invalid blur radius");
        sigma = Math.min(64f, sigma);
        int lower = Math.max(1, (int) Math.floor(Math.sqrt(4f * sigma * sigma + 1f)));
        if (lower % 2 == 0) lower--;
        int upper = lower + 2;
        int lowerCount = Math.max(0, Math.min(3, Math.round(
                (12f * sigma * sigma - 3f * lower * lower - 12f * lower - 9f) / (-4f * lower - 4f))));
        int[] radii = new int[3];
        for (int i = 0; i < 3; i++) radii[i] = ((i < lowerCount ? lower : upper) - 1) / 2;
        return radii;
    }

    private static void sweep(int[] source, int[] destination, int width, int height,
            int radius, boolean horizontal) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int step = horizontal ? 1 : width;
        int divisor = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int offset = horizontal ? line * width : line;
            int a = 0, r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = source[offset + Math.max(0, Math.min(length - 1, i)) * step];
                a += color >>> 24;
                r += color >>> 16 & 255;
                g += color >>> 8 & 255;
                b += color & 255;
            }
            for (int position = 0; position < length; position++) {
                destination[offset + position * step] = (a + divisor / 2) / divisor << 24
                        | (r + divisor / 2) / divisor << 16 | (g + divisor / 2) / divisor << 8
                        | (b + divisor / 2) / divisor;
                int remove = source[offset + Math.max(0, position - radius) * step];
                int add = source[offset + Math.min(length - 1, position + radius + 1) * step];
                a += (add >>> 24) - (remove >>> 24);
                r += (add >>> 16 & 255) - (remove >>> 16 & 255);
                g += (add >>> 8 & 255) - (remove >>> 8 & 255);
                b += (add & 255) - (remove & 255);
            }
        }
    }
}
