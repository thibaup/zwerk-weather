package com.zwerk.weather;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Single background fetch at a time, cancelled and retried when Android stops the job. */
public final class WidgetRefreshJobService extends JobService {
    private static final AtomicInteger WORKERS = new AtomicInteger();
    private final Handler main = new Handler(Looper.getMainLooper());
    // JobService callbacks and completion run on the main thread. Concurrent jobs
    // share this worker and all receive a completion rather than being discarded.
    private final HashMap<Integer, JobParameters> activeJobs = new HashMap<>();
    private Thread worker;

    static boolean isRunning() { return WORKERS.get() > 0; }

    @Override public boolean onStartJob(JobParameters params) {
        if (!WidgetRefreshManager.hasWidgets(this)) return false;
        activeJobs.put(params.getJobId(), params);
        if (worker != null) return true;
        WORKERS.incrementAndGet();
        DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.REFRESH_STARTED);
        worker = new Thread(() -> {
            boolean success = false;
            try { success = WidgetRefreshManager.refresh(getApplicationContext()); }
            catch (Exception failure) { DiagnosticLog.error(DiagnosticLog.Area.WIDGET, failure); }
            finally {
                final boolean completed = success;
                DiagnosticLog.event(DiagnosticLog.Area.WIDGET, completed
                        ? DiagnosticLog.Event.REFRESH_SUCCEEDED : DiagnosticLog.Event.REFRESH_PENDING);
                main.post(() -> {
                    ArrayList<JobParameters> finishing = new ArrayList<>(activeJobs.values());
                    activeJobs.clear();
                    worker = null;
                    WORKERS.decrementAndGet();
                    try {
                        boolean retry = !completed || WidgetRefreshManager.needsRefresh(getApplicationContext());
                        for (JobParameters item : finishing) jobFinished(item, retry);
                    } finally {
                        WidgetRefreshManager.reconcile(getApplicationContext());
                    }
                });
            }
        }, "ZwerkWidgetRefresh");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.REFRESH_STOPPED);
        if (activeJobs.get(params.getJobId()) == params) {
            activeJobs.remove(params.getJobId());
            if (activeJobs.isEmpty() && worker != null) worker.interrupt();
        }
        return WidgetRefreshManager.hasWidgets(this);
    }
}
