package app.sitestamp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Follows the phone's position from GPS and from the network, and looks up the
 * street address when there is internet. The address is only for the stamp; which
 * project the phone is on is worked out from the coordinates alone and needs no internet.
 */
final class LocationTracker implements LocationListener {

    interface Listener {
        void onLocation(Location location);

        void onAddress(String address);
    }

    private final Context context;
    private final Listener listener;
    private final LocationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean running;
    private Location best;
    private Location geocodedAt;
    private String address = "";
    private long lastGeocodeTry;
    private boolean geocoding;

    LocationTracker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean isGpsOn() {
        try {
            return manager != null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    Location last() {
        return best;
    }

    String address() {
        return address;
    }

    void start() {
        if (running || manager == null || !hasPermission(context)) return;
        running = true;
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        for (String p : providers) {
            try {
                if (!manager.getAllProviders().contains(p)) continue;
                Location l = manager.getLastKnownLocation(p);
                if (l != null && ageMs(l) < 120000) offer(l);
                manager.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {
                // This provider is not available to us; the other one may be.
            }
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        try {
            manager.removeUpdates(this);
        } catch (RuntimeException ignored) {
        }
    }

    void shutdown() {
        stop();
        worker.shutdownNow();
    }

    private static long ageMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1000000L;
    }

    private void offer(Location l) {
        if (best != null) {
            boolean bestIsGps = LocationManager.GPS_PROVIDER.equals(best.getProvider());
            boolean newIsGps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
            long newer = (l.getElapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1000000L;
            // A network position does not replace a GPS position that is still fresh.
            if (bestIsGps && !newIsGps && newer < 12000) return;
        }
        best = l;
        listener.onLocation(l);
        maybeGeocode(l);
    }

    private void maybeGeocode(final Location l) {
        if (geocoding || !Geocoder.isPresent()) return;
        long now = SystemClock.elapsedRealtime();
        boolean moved = geocodedAt == null || geocodedAt.distanceTo(l) > 40f;
        if (!moved) return;
        if (now - lastGeocodeTry < (address.isEmpty() ? 8000 : 4000)) return;
        lastGeocodeTry = now;
        geocoding = true;
        final double lat = l.getLatitude(), lon = l.getLongitude();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String found = null;
                try {
                    Geocoder g = new Geocoder(context, Locale.getDefault());
                    @SuppressWarnings("deprecation")
                    List<Address> list = g.getFromLocation(lat, lon, 1);
                    if (list != null && !list.isEmpty()) found = format(list.get(0));
                } catch (Exception ignored) {
                    // No internet, or no geocoder service. The stamp just has no address.
                }
                final String result = found;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        geocoding = false;
                        if (result != null && !result.isEmpty()) {
                            geocodedAt = l;
                            address = result;
                            listener.onAddress(result);
                        } else if (geocodedAt != null && geocodedAt.distanceTo(l) > 300f
                                && !address.isEmpty()) {
                            // Far from where the last address was found: it no longer applies.
                            address = "";
                            listener.onAddress("");
                        }
                    }
                });
            }
        });
    }

    private static String format(Address a) {
        String line = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : null;
        if (line != null && !line.trim().isEmpty()) return line.trim();
        StringBuilder sb = new StringBuilder();
        String[] parts = {a.getThoroughfare(), a.getSubLocality(), a.getLocality(),
                a.getAdminArea(), a.getCountryName()};
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (running && location != null) offer(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
