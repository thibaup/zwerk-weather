package com.zwerk.weather;

import android.app.Application;

public final class WeatherApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DiagnosticLog.initialize(this);
    }
}
