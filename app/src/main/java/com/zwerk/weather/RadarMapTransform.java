package com.zwerk.weather;

/** Projects a retained raster viewport onto a new camera without reloading map tiles. */
final class RadarMapTransform {
    final double centerX;
    final double centerY;
    final double worldSize;

    RadarMapTransform(double latitude, double longitude, float zoom, int tilePixels) {
        centerX = (longitude + 180d) / 360d;
        double sine = Math.sin(Math.toRadians(Math.max(-85.05112878d, Math.min(85.05112878d, latitude))));
        centerY = .5d - Math.log((1d + sine) / (1d - sine)) / (4d * Math.PI);
        worldSize = tilePixels * Math.pow(2d, zoom);
    }

    double scaleFrom(RadarMapTransform previous) { return worldSize / previous.worldSize; }

    double translationX(RadarMapTransform previous, int width) {
        double delta = previous.centerX - centerX;
        delta -= Math.floor(delta + .5d);
        return width * .5d * (1d - scaleFrom(previous)) + delta * worldSize;
    }

    double translationY(RadarMapTransform previous, int height) {
        return height * .5d * (1d - scaleFrom(previous))
                + (previous.centerY - centerY) * worldSize;
    }
}
