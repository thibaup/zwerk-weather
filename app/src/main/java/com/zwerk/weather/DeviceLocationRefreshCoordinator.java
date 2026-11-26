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

/** Checks device location before refreshes while preserving manually selected cities. */
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

    boolean isCheckingLocation() {
        return active && !awaitingPermission;
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
            fallbackLocation = bestLastKnownLocation(manager);
            String provider = preferredProvider(manager);
            if (provider == null) {
                if (fallbackLocation != null) finishWithLocation(fallbackLocation);
                else finishUnavailable();
                return;
            }

            if (Build.VERSION.SDK_INT >= 30) {
                requestCurrentLocationApi30(manager, provider, true);
                return;
            }

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

    @SuppressWarnings("MissingPermission")
    private void requestCurrentLocationApi30(
            LocationManager manager, String provider, boolean allowGpsRetry) {
        if (Build.VERSION.SDK_INT < 30) return;
        cancellationSignal = new CancellationSignal();
        mainHandler.removeCallbacks(timeoutRunnable);
        mainHandler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MILLIS);
        final CancellationSignal signal = cancellationSignal;
        manager.getCurrentLocation(
                provider,
                signal,
                activity.getMainExecutor(),
                location -> {
                    if (!active || cancellationSignal != signal) return;
                    if (location != null) {
                        finishWithLocation(location);
                        return;
                    }
                    if (allowGpsRetry
                            && LocationManager.NETWORK_PROVIDER.equals(provider)
                            && isProviderEnabledSafe(manager, LocationManager.GPS_PROVIDER)) {
                        try {
                            requestCurrentLocationApi30(manager, LocationManager.GPS_PROVIDER, false);
                            return;
                        } catch (Exception ignored) { }
                    }
                    finishWithLocation(fallbackLocation);
                });
    }

    private boolean isProviderEnabledSafe(LocationManager manager, String provider) {
        try {
            return manager.isProviderEnabled(provider);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String preferredProvider(LocationManager manager) {
        try {
            if (Build.VERSION.SDK_INT >= 31
                    && manager.hasProvider(LocationManager.FUSED_PROVIDER)
                    && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                return LocationManager.FUSED_PROVIDER;
            }
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
        Location best = null;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (manager.hasProvider(LocationManager.FUSED_PROVIDER)
                        && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                    best = newerLocation(best, manager.getLastKnownLocation(LocationManager.FUSED_PROVIDER));
                }
            } catch (Exception ignored) {
            }
        }
        try {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                best = newerLocation(best, manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
            }
        } catch (Exception ignored) {
        }
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                best = newerLocation(best, manager.getLastKnownLocation(LocationManager.GPS_PROVIDER));
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private static Location newerLocation(Location current, Location candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        return candidate.getTime() > current.getTime() ? candidate : current;
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
