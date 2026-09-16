package com.zwerk.weather;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

/**
 * Serializes device-location checks that must happen before MainActivity starts a weather refresh.
 * Normal refreshes never replace a deliberately selected manual location. Explicit "device
 * location" actions are allowed to switch back to the device location.
 */
final class DeviceLocationRefreshCoordinator {
    static final float LOCATION_CHANGE_THRESHOLD_METERS = 250f;
    private static final long LOCATION_TIMEOUT_MILLIS = 7000L;

    interface Callback {
        boolean isManualLocationProtected();
        int locationSelectionGeneration();
        void onLocationCheckStarted();
        void onDeviceLocationResolved(
                Location location,
                boolean explicitDeviceSelection,
                boolean forceNetwork,
                int selectionGeneration);
        void onLocationCheckUnavailable(boolean forceNetwork);
    }

    private final Activity activity;
    private final int permissionRequestCode;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean active;
    private boolean awaitingPermission;
    private boolean explicitDeviceSelection;
    private boolean forceNetwork;
    private boolean pendingExplicitDeviceSelection;
    private boolean pendingForceNetwork;
    private int selectionGeneration;
    private CancellationSignal cancellationSignal;
    private LocationManager activeLocationManager;
    private LocationListener activeListener;
    private Location fallbackLocation;
    private final Runnable timeoutRunnable = this::onLocationTimeout;

    DeviceLocationRefreshCoordinator(Activity activity, int permissionRequestCode, Callback callback) {
        this.activity = activity;
        this.permissionRequestCode = permissionRequestCode;
        this.callback = callback;
    }

    void refresh(boolean forceNetwork, boolean allowPermissionPrompt) {
        request(false, forceNetwork, allowPermissionPrompt);
    }

    void selectDeviceLocation(boolean forceNetwork) {
        request(true, forceNetwork, true);
    }

    void onPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != permissionRequestCode || !active || !awaitingPermission) return;
        awaitingPermission = false;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            startLocationLookup();
        } else {
            finishUnavailable();
        }
    }

    void onSelectionChanged() {
        if (!active) return;
        cancelLookup();
        clearActiveState();
        clearPendingState();
    }

    void destroy() {
        cancelLookup();
        clearActiveState();
        pendingExplicitDeviceSelection = false;
        pendingForceNetwork = false;
    }

    private void request(
            boolean explicitDeviceSelection,
            boolean forceNetwork,
            boolean allowPermissionPrompt) {
        if (!explicitDeviceSelection && callback.isManualLocationProtected()) {
            callback.onLocationCheckUnavailable(forceNetwork);
            return;
        }

        if (active) {
            pendingExplicitDeviceSelection |= explicitDeviceSelection;
            pendingForceNetwork |= forceNetwork;
            return;
        }

        active = true;
        this.explicitDeviceSelection = explicitDeviceSelection;
        this.forceNetwork = forceNetwork;
        this.selectionGeneration = callback.locationSelectionGeneration();

        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            if (allowPermissionPrompt) {
                awaitingPermission = true;
                activity.requestPermissions(
                        new String[]{
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        },
                        permissionRequestCode);
            } else {
                finishUnavailable();
            }
            return;
        }

        startLocationLookup();
    }

    @SuppressWarnings("MissingPermission")
    private void startLocationLookup() {
        if (!active) return;
        if (!explicitDeviceSelection && callback.isManualLocationProtected()) {
            finishUnavailable();
            return;
        }

        callback.onLocationCheckStarted();
        LocationManager manager = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
        if (manager == null) {
            finishUnavailable();
            return;
        }

        try {
            String provider = preferredProvider(manager);
            if (provider == null) {
                finishUnavailable();
                return;
            }

            if (Build.VERSION.SDK_INT >= 30) {
                cancellationSignal = new CancellationSignal();
                mainHandler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MILLIS);
                manager.getCurrentLocation(
                        provider,
                        cancellationSignal,
                        activity.getMainExecutor(),
                        this::finishWithLocation);
                return;
            }

            fallbackLocation = bestLastKnownLocation(manager);
            activeLocationManager = manager;
            activeListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    finishWithLocation(location);
                }

                @Override public void onProviderEnabled(String provider) { }

                @Override
                public void onProviderDisabled(String provider) {
                    finishWithLocation(fallbackLocation);
                }

                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            };
            mainHandler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MILLIS);
            manager.requestSingleUpdate(provider, activeListener, Looper.getMainLooper());
        } catch (Exception ignored) {
            finishUnavailable();
        }
    }

    private String preferredProvider(LocationManager manager) {
        try {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("MissingPermission")
    private Location bestLastKnownLocation(LocationManager manager) {
        Location network = null;
        Location gps = null;
        try {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception ignored) {
        }
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception ignored) {
        }
        if (network == null) return gps;
        if (gps == null) return network;
        return gps.getTime() > network.getTime() ? gps : network;
    }

    private void onLocationTimeout() {
        if (!active || awaitingPermission) return;
        finishWithLocation(fallbackLocation);
    }

    private void finishWithLocation(Location location) {
        if (!active) return;
        boolean explicit = explicitDeviceSelection || pendingExplicitDeviceSelection;
        boolean force = forceNetwork || pendingForceNetwork;
        int generation = selectionGeneration;
        cancelLookup();
        clearActiveState();
        clearPendingState();

        if (location != null
                && (explicit
                        || (!callback.isManualLocationProtected()
                                && generation == callback.locationSelectionGeneration()))) {
            callback.onDeviceLocationResolved(location, explicit, force, generation);
        } else {
            callback.onLocationCheckUnavailable(force);
        }
    }

    private void finishUnavailable() {
        if (!active) return;
        boolean force = forceNetwork || pendingForceNetwork;
        cancelLookup();
        clearActiveState();
        clearPendingState();
        callback.onLocationCheckUnavailable(force);
    }

    @SuppressWarnings("MissingPermission")
    private void cancelLookup() {
        mainHandler.removeCallbacks(timeoutRunnable);
        if (cancellationSignal != null) {
            try {
                cancellationSignal.cancel();
            } catch (Exception ignored) {
            }
        }
        if (activeLocationManager != null && activeListener != null) {
            try {
                activeLocationManager.removeUpdates(activeListener);
            } catch (Exception ignored) {
            }
        }
        cancellationSignal = null;
        activeLocationManager = null;
        activeListener = null;
        fallbackLocation = null;
    }

    private void clearActiveState() {
        active = false;
        awaitingPermission = false;
        explicitDeviceSelection = false;
        forceNetwork = false;
        selectionGeneration = 0;
    }

    private void clearPendingState() {
        pendingExplicitDeviceSelection = false;
        pendingForceNetwork = false;
    }
}
