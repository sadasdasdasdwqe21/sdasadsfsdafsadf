package app.sitestamp;

import java.util.List;

/**
 * Decides which project the phone is standing on. Plain Java, tested on a PC.
 *
 * A project matches while the position is inside its radius. Once matched, it stays
 * matched a little beyond the radius, so the name does not flicker at the fence.
 */
final class SiteMatcher {

    static final class Result {
        /** The project the phone is on, or null when it is on none. */
        Project project;
        /** Distance to that project's centre point, in metres. */
        double distanceM;
        /** The closest project of all, on site or not. Null when the list is empty. */
        Project nearest;
        double nearestM;
    }

    private String currentId;

    /** Forget the remembered project, for example after the list was edited. */
    void reset() {
        currentId = null;
    }

    Result update(List<Project> projects, double lat, double lon) {
        Result r = new Result();
        Project best = null;
        double bestD = Double.MAX_VALUE;
        Project held = null;
        double heldD = Double.MAX_VALUE;

        for (Project p : projects) {
            double d = Geo.distanceM(lat, lon, p.lat, p.lon);
            if (d < r.nearestM || r.nearest == null) {
                r.nearest = p;
                r.nearestM = d;
            }
            if (d <= p.radiusM && d < bestD) {
                best = p;
                bestD = d;
            }
            if (p.id.equals(currentId) && d <= p.radiusM + keepMargin(p.radiusM)) {
                held = p;
                heldD = d;
            }
        }

        if (best == null && held != null) {
            // Just outside the fence of the site we were on: keep it.
            best = held;
            bestD = heldD;
        }
        if (best != null) {
            r.project = best;
            r.distanceM = bestD;
            currentId = best.id;
        } else {
            currentId = null;
        }
        return r;
    }

    static double keepMargin(double radiusM) {
        return Math.max(25, radiusM * 0.2);
    }
}
