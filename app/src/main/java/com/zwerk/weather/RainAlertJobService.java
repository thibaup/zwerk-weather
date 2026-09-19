package com.zwerk.weather;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/** Periodic network-constrained worker for imminent rain checks. */
public final class RainAlertJobService extends JobService {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile JobParameters activeParams;
    private volatile boolean stopped;
    private volatile Thread workerThread;

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!RainAlertManager.isEnabled(this)) {
            RainAlertManager.reconcile(this);
            return false;
        }
        if (!RUNNING.compareAndSet(false, true)) return false;

        stopped = false;
        activeParams = params;
        Thread worker = new Thread(() -> {
            try {
                if (!stopped) RainAlertManager.performBackgroundCheck(getApplicationContext());
            } finally {
                boolean posted = mainHandler.post(() -> {
                    try {
                        if (activeParams == params && !stopped) {
                            jobFinished(params, false);
                        }
                    } finally {
                        if (activeParams == params) activeParams = null;
                        workerThread = null;
                        RUNNING.set(false);
                    }
                });
                if (!posted) {
                    activeParams = null;
                    workerThread = null;
                    RUNNING.set(false);
                }
            }
        }, "ZwerkRainAlert");
        workerThread = worker;
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (activeParams == params) {
            stopped = true;
            Thread worker = workerThread;
            if (worker != null) worker.interrupt();
        }
        return false;
    }
}
