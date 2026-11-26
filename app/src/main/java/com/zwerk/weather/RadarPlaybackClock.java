package com.zwerk.weather;

/** Bounds readiness waits while keeping playback within its two-frame lookahead window. */
final class RadarPlaybackClock {
    static final int WAIT = 0;
    static final int ADVANCE = 1;
    static final int SKIP = 2;
    static final int STOP = 3;
    private static final long CURRENT_WAIT_MS = 30_000L;
    private static final long NEXT_WAIT_MS = 5_000L;

    private long currentWaitStarted = -1L;
    private long nextWaitStarted = -1L;
    private int offset = 1;

    static float normalizeSpeed(float speed) {
        if (Math.abs(speed - 0.5f) < 0.05f) return 0.5f;
        if (Math.abs(speed - 2.0f) < 0.05f) return 2.0f;
        return 1.0f;
    }

    static float nextSpeed(float current) {
        float normalized = normalizeSpeed(current);
        if (normalized < 0.75f) return 1.0f;
        if (normalized < 1.5f) return 2.0f;
        return 0.5f;
    }

    static long frameDelayMillis(long baseDelayMillis, float playbackSpeed,
            int nextIndex, int frameCount) {
        float speed = normalizeSpeed(playbackSpeed);
        long delay = Math.round(baseDelayMillis / speed);
        if (frameCount > 1 && nextIndex == frameCount - 1) {
            return Math.max(1300L, delay + 650L);
        }
        return delay;
    }

    int candidateOffset() { return offset; }

    void reset() {
        offset = 1;
        currentWaitStarted = -1L;
        nextWaitStarted = -1L;
    }

    int step(long now, boolean moving, boolean currentReady, boolean nextReady, int frameCount) {
        return step(now, moving, currentReady, nextReady, frameCount, 0L);
    }

    int step(long now, boolean moving, boolean currentReady, boolean nextReady, int frameCount,
            long cooldownMillis) {
        if (frameCount < 2) return STOP;
        if (moving) {
            currentWaitStarted = nextWaitStarted = -1L;
            return WAIT;
        }
        if (currentReady && nextReady) {
            reset();
            return ADVANCE;
        }
        if (cooldownMillis > 0L) {
            currentWaitStarted = nextWaitStarted = -1L;
            return WAIT;
        }
        if (!currentReady) {
            nextWaitStarted = -1L;
            if (currentWaitStarted < 0L) currentWaitStarted = now;
            return now - currentWaitStarted >= CURRENT_WAIT_MS ? STOP : WAIT;
        }
        currentWaitStarted = -1L;
        if (nextWaitStarted < 0L) nextWaitStarted = now;
        if (now - nextWaitStarted < NEXT_WAIT_MS) return WAIT;
        if (offset < Math.min(2, frameCount - 1)) {
            offset++;
            nextWaitStarted = -1L;
            return SKIP;
        }
        return STOP;
    }
}
