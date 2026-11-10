package app.sitestamp;

/** One project site: a name, two optional extra lines, and a circle on the map. */
final class Project {
    String id = "";
    /** The project name written on the stamp. */
    String name = "";
    /** Optional line above the name, for example the company or contractor. */
    String company = "";
    /** Optional line below the name, for example the town or zone. */
    String place = "";
    double lat;
    double lon;
    /** How far from the centre point still counts as being on this site. */
    double radiusM = 150;

    Project copy() {
        Project p = new Project();
        p.id = id;
        p.name = name;
        p.company = company;
        p.place = place;
        p.lat = lat;
        p.lon = lon;
        p.radiusM = radiusM;
        return p;
    }
}
