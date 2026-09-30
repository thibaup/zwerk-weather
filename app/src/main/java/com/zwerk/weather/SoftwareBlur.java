package com.zwerk.weather;

import android.graphics.Bitmap;

/** Reuses working buffers for the pre-Android-12 backdrop blur. */
final class SoftwareBlur {
    private int[] pixels;
    private int[] scratch;

    void apply(Bitmap bitmap, float sigmaX, float sigmaY) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int count = width * height;
        if (pixels == null || pixels.length != count) {
            pixels = new int[count];
            scratch = new int[count];
        }
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        BlurKernel.blur(pixels, scratch, width, height, sigmaX, sigmaY);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    void release() { pixels = null; scratch = null; }
}
