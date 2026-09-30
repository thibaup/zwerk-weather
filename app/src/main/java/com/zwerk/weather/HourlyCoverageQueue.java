package com.zwerk.weather;

import java.time.LocalDate;

/** Keeps queued day selections owned by the forecast that started their worker. */
final class HourlyCoverageQueue {
    private Object owner;
    private LocalDate target;
    private LocalDate loadingTarget;
    private boolean active;

    synchronized void reset() {
        owner = null;
        target = loadingTarget = null;
        active = false;
    }

    synchronized boolean request(Object forecast, LocalDate date) {
        if (owner != forecast) reset();
        owner = forecast;
        target = loadingTarget = date;
        if (active) return false;
        active = true;
        return true;
    }

    synchronized boolean beginRetry(Object forecast) {
        if (owner == forecast && active) return false;
        reset();
        owner = forecast;
        active = true;
        return true;
    }

    synchronized LocalDate targetFor(Object forecast) {
        return owner == forecast ? target : null;
    }

    synchronized void cancel(Object forecast, LocalDate date) {
        if (owner != forecast || date == null) return;
        if (date.equals(target)) target = null;
        if (date.equals(loadingTarget)) loadingTarget = null;
    }

    synchronized void fail(Object forecast) {
        if (owner == forecast) target = loadingTarget = null;
    }

    /** Returns true if a selection arrived just before this worker finished. */
    synchronized boolean finish(Object forecast, boolean current) {
        if (owner != forecast) return false;
        if (current && target != null) {
            loadingTarget = target;
            return true;
        }
        active = false;
        loadingTarget = null;
        if (!current) target = null;
        return false;
    }

    synchronized boolean isLoading(Object forecast, LocalDate date) {
        return active && owner == forecast && date != null && date.equals(loadingTarget);
    }
}
