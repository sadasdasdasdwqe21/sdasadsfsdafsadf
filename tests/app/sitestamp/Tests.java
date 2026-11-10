package app.sitestamp;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Runs on a PC with only a JDK. See tests/run.sh. */
public final class Tests {
    private static int passed, failed;

    public static void main(String[] args) {
        distance();
        parsing();
        coordinateText();
        matching();
        names();
        stampLines();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void ok(boolean cond, String what) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void near(double got, double want, double tol, String what) {
        ok(Math.abs(got - want) <= tol, what + " got " + got + " want " + want);
    }

    private static void eq(String got, String want, String what) {
        ok(want.equals(got), what + " got [" + got + "] want [" + want + "]");
    }

    private static void distance() {
        near(Geo.distanceM(0, 0, 0, 0), 0, 1e-9, "zero distance");
        // One degree of latitude is about 111.2 km.
        near(Geo.distanceM(-2, 33, -3, 33), 111195, 50, "one degree of latitude");
        // 0.001 degree of longitude near Mwanza is about 111 m.
        near(Geo.distanceM(-2.5164, 32.9175, -2.5164, 32.9185), 111.1, 0.5, "0.001 deg lon");
        near(Geo.distanceM(-2.5164, 32.9175, -2.5173, 32.9175), 100.1, 0.5, "0.0009 deg lat");
    }

    private static void parsing() {
        double[] a = Geo.parseLatLon("-2.516400, 32.917500");
        ok(a != null, "plain pair parses");
        near(a[0], -2.5164, 1e-9, "plain lat");
        near(a[1], 32.9175, 1e-9, "plain lon");

        a = Geo.parseLatLon("  -2.5164 32.9175 ");
        ok(a != null && Math.abs(a[0] + 2.5164) < 1e-9, "space separated");

        a = Geo.parseLatLon("2.5164 S 32.9175 E");
        ok(a != null, "hemisphere letters parse");
        near(a[0], -2.5164, 1e-9, "S is negative");
        near(a[1], 32.9175, 1e-9, "E is positive");

        a = Geo.parseLatLon("2°30'59.0\"S 32°55'03.0\"E");
        ok(a != null, "dms parses");
        near(a[0], -(2 + 30 / 60.0 + 59 / 3600.0), 1e-9, "dms lat");
        near(a[1], 32 + 55 / 60.0 + 3 / 3600.0, 1e-9, "dms lon");

        a = Geo.parseLatLon("E 32.9175, S 2.5164");
        ok(a != null, "lon first parses");
        near(a[0], -2.5164, 1e-9, "lon first lat");
        near(a[1], 32.9175, 1e-9, "lon first lon");

        a = Geo.parseLatLon("Lat: -2.5164  Long: 32.9175");
        ok(a != null && Math.abs(a[1] - 32.9175) < 1e-9, "labels are ignored");

        a = Geo.parseLatLon("2 30.5 S, 32 55.25 E");
        ok(a != null, "degrees and minutes parse");
        near(a[0], -(2 + 30.5 / 60.0), 1e-9, "dm lat");

        ok(Geo.parseLatLon("") == null, "empty is rejected");
        ok(Geo.parseLatLon("hello") == null, "words are rejected");
        ok(Geo.parseLatLon("95, 20") == null, "latitude above 90 is rejected");
        ok(Geo.parseLatLon("10, 200") == null, "longitude above 180 is rejected");
        ok(Geo.parseLatLon("1, 2, 3") == null, "three numbers are rejected");
        ok(Geo.parseLatLon("2.5 S 3.5 S") == null, "two latitudes are rejected");
        ok(Geo.parseLatLon(null) == null, "null is rejected");
    }

    private static void coordinateText() {
        eq(Geo.decimal(-2.5164, 32.9175), "2.51640°S  32.91750°E", "decimal text");
        eq(Geo.decimal(51.5, -0.12), "51.50000°N  0.12000°W", "decimal text north west");
        eq(Geo.dms(-2.5163889, 32.9175), "2°30'59.0\"S  32°55'03.0\"E", "dms text");
        eq(Geo.dms(0.99999999, 0), "1°00'00.0\"N  0°00'00.0\"E", "dms rounding carries");
        eq(Geo.plain(-2.5164, 32.9175), "-2.516400, 32.917500", "plain text");
        double[] back = Geo.parseLatLon(Geo.dms(-2.5163889, 32.9175));
        ok(back != null && Math.abs(back[0] + 2.5163889) < 2e-5, "dms text reads back");
        back = Geo.parseLatLon(Geo.decimal(-2.5164, 32.9175));
        ok(back != null && Math.abs(back[0] + 2.5164) < 1e-5 && Math.abs(back[1] - 32.9175) < 1e-5,
                "decimal text reads back");
        eq(Geo.distanceLabel(34.6), "35 m", "metres label");
        eq(Geo.distanceLabel(2340), "2.3 km", "km label");
        eq(Geo.distanceLabel(48200), "48 km", "long km label");
    }

    private static Project project(String id, String name, double lat, double lon, double r) {
        Project p = new Project();
        p.id = id;
        p.name = name;
        p.lat = lat;
        p.lon = lon;
        p.radiusM = r;
        return p;
    }

    private static void matching() {
        List<Project> list = new ArrayList<>();
        Project a = project("a", "Site A", -2.5164, 32.9175, 150);
        Project b = project("b", "Site B", -2.8700, 32.2300, 200);
        Project c = project("c", "Site C", -2.5164, 32.9200, 150); // about 278 m east of A
        list.add(a);
        list.add(b);
        list.add(c);

        SiteMatcher m = new SiteMatcher();
        SiteMatcher.Result r = m.update(list, -2.5164, 32.9175);
        ok(r.project == a, "standing on A matches A");
        near(r.distanceM, 0, 0.01, "distance on A");

        r = m.update(list, -2.8705, 32.2302);
        ok(r.project == b, "standing on B matches B");

        // Far from everything.
        m.reset();
        r = m.update(list, -2.60, 32.80);
        ok(r.project == null, "far away matches nothing");
        ok(r.nearest == a, "nearest is still reported");
        ok(r.nearestM > 10000, "nearest distance is reported");

        // 160 m from A: outside the 150 m radius. Fresh arrival does not match.
        double lat160 = -2.5164 - 160 / 111195.0;
        m.reset();
        r = m.update(list, lat160, 32.9175);
        ok(r.project == null, "160 m away does not match on arrival");

        // But when already on A, walking to 160 m keeps A (inside the keep margin of 30 m).
        m.reset();
        m.update(list, -2.5164, 32.9175);
        r = m.update(list, lat160, 32.9175);
        ok(r.project == a, "160 m away keeps the site we were on");

        // At 190 m it lets go.
        r = m.update(list, -2.5164 - 190 / 111195.0, 32.9175);
        ok(r.project == null, "190 m away lets go");
        r = m.update(list, lat160, 32.9175);
        ok(r.project == null, "coming back to 160 m does not match again");

        // Between A and C, closer to C and inside both radii would pick the closer one.
        Project wide = project("w", "Wide", -2.5164, 32.9180, 400);
        list.add(wide);
        m.reset();
        r = m.update(list, -2.5164, 32.9176);
        ok(r.project == a, "overlapping sites: the closer centre wins");
        r = m.update(list, -2.5164, 32.9181);
        ok(r.project == wide, "overlapping sites: switches when another centre is closer");

        // Empty list.
        m.reset();
        r = m.update(new ArrayList<Project>(), 0, 0);
        ok(r.project == null && r.nearest == null, "empty list matches nothing");

        near(SiteMatcher.keepMargin(50), 25, 1e-9, "small radius keeps 25 m");
        near(SiteMatcher.keepMargin(500), 100, 1e-9, "large radius keeps 20 percent");
    }

    private static void names() {
        eq(Names.safe("  Katoro  NCU "), "Katoro NCU", "spaces are tidied");
        eq(Names.safe("Block A/B: Phase*2?"), "Block A B Phase 2", "bad characters go");
        eq(Names.safe("..hidden."), "hidden", "dots at the ends go");
        eq(Names.safe(null), "", "null name");
        ok(Names.safe("An extremely long project name that keeps going on and on").length() <= 40,
                "long names are cut");
        eq(Names.folder(null), "Other", "no project folder");
        Project p = project("a", "Zonal Office", 0, 0, 100);
        eq(Names.folder(p), "Zonal Office", "project folder");
        TimeZone tz = TimeZone.getTimeZone("Africa/Dar_es_Salaam");
        // 2026-11-10 15:03:22 in Dar es Salaam is 12:03:22 UTC.
        long t = 1794312202000L;
        eq(Names.file(p, t, tz, "jpg"), "Zonal Office_20261110_150322.jpg", "photo file name");
        eq(Names.file(null, t, tz, "mp4"), "Photo_20261110_150322.mp4", "file name without project");
    }

    private static void stampLines() {
        TimeZone tz = TimeZone.getTimeZone("Africa/Dar_es_Salaam");
        long t = 1794312202000L;
        StampText.Options o = new StampText.Options();
        o.defaultCompany = "MY COMPANY LTD";
        Project p = project("a", "ZONAL OFFICE BUILDING", -2.5164, 32.9175, 150);
        p.place = "LAKE ZONE - MWANZA";
        StampText.Fix f = new StampText.Fix();
        f.has = true;
        f.lat = -2.5164;
        f.lon = 32.9175;
        f.address = "Station Road, Mwanza";

        List<StampText.Line> lines = StampText.build(o, p, f, t, tz);
        ok(lines.size() == 6, "on site: six lines, got " + lines.size());
        eq(lines.get(0).text, "MY COMPANY LTD", "default company line");
        ok(lines.get(0).role == StampText.ROLE_COMPANY, "company role");
        eq(lines.get(1).text, "ZONAL OFFICE BUILDING", "project line");
        ok(lines.get(1).role == StampText.ROLE_PROJECT, "project role");
        eq(lines.get(2).text, "LAKE ZONE - MWANZA", "place line");
        eq(lines.get(3).text, "10 Nov 2026  15:03:22", "date line");
        eq(lines.get(4).text, "Station Road, Mwanza", "address line");
        eq(lines.get(5).text, "2.51640°S  32.91750°E", "coordinate line");

        // The project's own company line wins over the default.
        p.company = "OTHER CONTRACTOR";
        lines = StampText.build(o, p, f, t, tz);
        eq(lines.get(0).text, "OTHER CONTRACTOR", "project company line");

        // Off site: no project name or place, address and coordinates stay.
        lines = StampText.build(o, null, f, t, tz);
        ok(lines.size() == 4, "off site: four lines, got " + lines.size());
        eq(lines.get(0).text, "MY COMPANY LTD", "off site keeps the default company");
        eq(lines.get(1).text, "10 Nov 2026  15:03:22", "off site date");
        eq(lines.get(2).text, "Station Road, Mwanza", "off site address");

        // No GPS yet.
        lines = StampText.build(o, null, new StampText.Fix(), t, tz);
        eq(lines.get(lines.size() - 1).text, "Waiting for GPS", "no fix text");

        // Extras.
        o.showAltitude = true;
        o.showAccuracy = true;
        o.showNote = true;
        o.note = "Slab casting";
        o.dms = true;
        o.datePattern = 2;
        f.hasAlt = true;
        f.alt = 1140.4;
        f.hasAcc = true;
        f.acc = 4.6f;
        lines = StampText.build(o, p, f, t, tz);
        eq(lines.get(3).text, "2026-11-10  15:03:22", "iso date");
        eq(lines.get(5).text, "2°30'59.0\"S  32°55'03.0\"E", "dms coordinate line");
        eq(lines.get(6).text, "Alt 1140 m   GPS ±5 m", "altitude and accuracy line");
        eq(lines.get(7).text, "Slab casting", "note line");

        // Everything off gives nothing.
        StampText.Options none = new StampText.Options();
        none.showCompany = none.showProject = none.showPlace = none.showDate = false;
        none.showAddress = none.showCoords = false;
        ok(StampText.build(none, p, f, t, tz).isEmpty(), "all off gives no lines");
        eq(StampText.date(99, t, tz), "10 Nov 2026  15:03:22", "bad pattern index falls back");
    }
}
