package app.shakefinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the answers of an ELM327 OBD2 adapter. Plain Java, no Android imports.
 *
 * The adapter answers "010C" (engine speed) with something like
 * "41 0C 1A F8": mode 41, PID 0C, then two data bytes. Engine speed is
 * (256*A + B) / 4 rpm. "010D" (road speed) answers "41 0D 3C": one byte, km/h.
 */
public final class Elm327 {

    private Elm327() {}

    /** Strips prompts, spaces, line ends and the adapter's chatter; upper-cases the rest. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.toUpperCase(Locale.US)
                .replace("SEARCHING...", "")
                .replace("BUS INIT: OK", "")
                .replace("BUS INIT:", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '>' || c == ' ' || c == '\0') continue;
            sb.append(c == '\n' ? '\r' : c);
        }
        return sb.toString();
    }

    /** True when the adapter says the car isn't answering. */
    public static boolean noData(String cleaned) {
        return cleaned.contains("NODATA") || cleaned.contains("UNABLETOCONNECT") || cleaned.contains("CANERROR")
                || cleaned.contains("BUSERROR") || cleaned.contains("ERROR") || cleaned.contains("STOPPED");
    }

    /** True when the adapter did not understand the command. */
    public static boolean rejected(String cleaned) {
        return cleaned.contains("?");
    }

    private static int hexAt(String s, int from, int digits) {
        if (from < 0 || from + digits > s.length()) return -1;
        int v = 0;
        for (int i = from; i < from + digits; i++) {
            int d = Character.digit(s.charAt(i), 16);
            if (d < 0) return -1;
            v = v * 16 + d;
        }
        return v;
    }

    private static String hex2(int v) {
        return String.format(Locale.US, "%02X", v & 0xFF);
    }

    /**
     * Finds the data bytes that follow a key such as "410C" in a cleaned answer.
     * Lines that start with the key are trusted first; after that any place it
     * appears, which covers adapters that send headers or a length byte.
     *
     * @return the bytes, or null
     */
    static int[] bytesAfter(String cleaned, String key, int count) {
        String[] lines = cleaned.split("\r");
        for (int pass = 0; pass < 2; pass++) {
            for (String line : lines) {
                int from = 0;
                while (true) {
                    int i = pass == 0 ? (line.startsWith(key) ? 0 : -1) : line.indexOf(key, from);
                    if (i < 0) break;
                    int[] out = new int[count];
                    boolean ok = true;
                    for (int b = 0; b < count; b++) {
                        out[b] = hexAt(line, i + key.length() + 2 * b, 2);
                        if (out[b] < 0) { ok = false; break; }
                    }
                    if (ok) return out;
                    if (pass == 0) break;
                    from = i + 1;
                }
            }
        }
        return null;
    }

    /** Engine speed in rpm from an answer to 010C, or NaN. */
    public static double parseRpm(String cleaned) {
        int[] d = bytesAfter(cleaned, "410C", 2);
        return d == null ? Double.NaN : (d[0] * 256 + d[1]) / 4.0;
    }

    /** Road speed in km/h from an answer to 010D, or NaN. */
    public static double parseSpeed(String cleaned) {
        int[] d = bytesAfter(cleaned, "410D", 1);
        return d == null ? Double.NaN : d[0];
    }

    /** True when an answer to 0100 shows the car is talking. */
    public static boolean ecuAnswered(String cleaned) {
        return cleaned.contains("4100");
    }

    // ------------------------------------------------------------------ live data

    interface Fn { double f(int a, int b, int c, int d); }

    /** One live value the ECU can report. */
    public static final class Pid {
        public final int id;
        public final String name, unit;
        public final int bytes, decimals;
        public final double lo, hi; // usual range, for the tile's bar
        final Fn fn;

        Pid(int id, String name, String unit, int bytes, int decimals, double lo, double hi, Fn fn) {
            this.id = id;
            this.name = name;
            this.unit = unit;
            this.bytes = bytes;
            this.decimals = decimals;
            this.lo = lo;
            this.hi = hi;
            this.fn = fn;
        }

        /** The command for live data, e.g. "010C". */
        public String command() {
            return "01" + hex2(id);
        }

        public String format(double v) {
            return Double.isNaN(v) ? "–" : String.format(Locale.US, "%." + decimals + "f", v);
        }
    }

    // Ids above 0xFF are values the app works out itself.
    public static final int BOOST = 0x100, ECONOMY = 0x101;

    /** In display order. */
    public static final Pid[] PIDS = {
            new Pid(0x0C, "Engine speed", "rpm", 2, 0, 0, 7000, (a, b, c, d) -> (a * 256 + b) / 4.0),
            new Pid(0x0D, "Road speed", "km/h", 1, 0, 0, 180, (a, b, c, d) -> a),
            new Pid(0x04, "Engine load", "%", 1, 0, 0, 100, (a, b, c, d) -> a * 100 / 255.0),
            new Pid(0x11, "Throttle", "%", 1, 0, 0, 100, (a, b, c, d) -> a * 100 / 255.0),
            new Pid(0x49, "Accelerator pedal", "%", 1, 0, 0, 100, (a, b, c, d) -> a * 100 / 255.0),
            new Pid(0x05, "Coolant", "°C", 1, 0, 0, 120, (a, b, c, d) -> a - 40),
            new Pid(0x5C, "Engine oil", "°C", 1, 0, 0, 140, (a, b, c, d) -> a - 40),
            new Pid(0x0F, "Intake air", "°C", 1, 0, 0, 80, (a, b, c, d) -> a - 40),
            new Pid(0x46, "Outside air", "°C", 1, 0, 0, 50, (a, b, c, d) -> a - 40),
            new Pid(0x0B, "Manifold pressure", "kPa", 1, 0, 0, 255, (a, b, c, d) -> a),
            new Pid(BOOST, "Boost", "kPa", 0, 0, -80, 150, null),
            new Pid(0x33, "Barometer", "kPa", 1, 0, 60, 110, (a, b, c, d) -> a),
            new Pid(0x10, "Air flow (MAF)", "g/s", 2, 1, 0, 200, (a, b, c, d) -> (a * 256 + b) / 100.0),
            new Pid(0x0E, "Ignition advance", "°", 1, 1, -20, 50, (a, b, c, d) -> a / 2.0 - 64),
            new Pid(0x06, "Short fuel trim", "%", 1, 1, -25, 25, (a, b, c, d) -> (a - 128) * 100 / 128.0),
            new Pid(0x07, "Long fuel trim", "%", 1, 1, -25, 25, (a, b, c, d) -> (a - 128) * 100 / 128.0),
            new Pid(0x08, "Short trim bank 2", "%", 1, 1, -25, 25, (a, b, c, d) -> (a - 128) * 100 / 128.0),
            new Pid(0x09, "Long trim bank 2", "%", 1, 1, -25, 25, (a, b, c, d) -> (a - 128) * 100 / 128.0),
            new Pid(0x34, "Lambda (A/F sensor)", "λ", 4, 3, 0.7, 1.3, (a, b, c, d) -> (a * 256 + b) * 2 / 65536.0),
            new Pid(0x24, "Lambda (A/F sensor)", "λ", 4, 3, 0.7, 1.3, (a, b, c, d) -> (a * 256 + b) * 2 / 65536.0),
            new Pid(0x44, "Commanded lambda", "λ", 2, 3, 0.7, 1.3, (a, b, c, d) -> (a * 256 + b) * 2 / 65536.0),
            new Pid(0x15, "Rear O2 sensor", "V", 2, 2, 0, 1.2, (a, b, c, d) -> a / 200.0),
            new Pid(ECONOMY, "Fuel economy", "km/L", 0, 1, 0, 30, null),
            new Pid(0x5E, "Fuel rate", "L/h", 2, 1, 0, 30, (a, b, c, d) -> (a * 256 + b) / 20.0),
            new Pid(0x2F, "Fuel level", "%", 1, 0, 0, 100, (a, b, c, d) -> a * 100 / 255.0),
            new Pid(0x42, "ECU voltage", "V", 2, 2, 10, 15.5, (a, b, c, d) -> (a * 256 + b) / 1000.0),
            new Pid(0x43, "Absolute load", "%", 2, 0, 0, 200, (a, b, c, d) -> (a * 256 + b) * 100 / 255.0),
            new Pid(0x3C, "Catalyst", "°C", 2, 0, 0, 900, (a, b, c, d) -> (a * 256 + b) / 10.0 - 40),
            new Pid(0x23, "Fuel rail pressure", "kPa", 2, 0, 0, 20000, (a, b, c, d) -> (a * 256 + b) * 10.0),
            new Pid(0x0A, "Fuel pressure", "kPa", 1, 0, 0, 765, (a, b, c, d) -> a * 3.0),
            new Pid(0x2C, "Commanded EGR", "%", 1, 0, 0, 100, (a, b, c, d) -> a * 100 / 255.0),
            new Pid(0x2E, "Evap purge", "%", 1, 0, 0, 100, (a, b, c, d) -> a * 100 / 255.0),
            new Pid(0x1F, "Engine run time", "s", 2, 0, 0, 3600, (a, b, c, d) -> a * 256 + b),
            new Pid(0x31, "Since codes cleared", "km", 2, 0, 0, 65535, (a, b, c, d) -> a * 256 + b),
            new Pid(0x21, "Driven with MIL on", "km", 2, 0, 0, 65535, (a, b, c, d) -> a * 256 + b),
            new Pid(0x30, "Warm-ups since clear", "", 1, 0, 0, 255, (a, b, c, d) -> a),
    };

    public static Pid pid(int id) {
        for (Pid p : PIDS) if (p.id == id) return p;
        return null;
    }

    /**
     * Reads one value from an answer.
     *
     * @param mode 1 for live data, 2 for the freeze frame
     */
    public static double parse(String cleaned, int mode, Pid p) {
        if (p == null || p.fn == null) return Double.NaN;
        String key = (mode == 2 ? "42" : "41") + hex2(p.id) + (mode == 2 ? "00" : "");
        int[] d = bytesAfter(cleaned, key, p.bytes);
        if (d == null) return Double.NaN;
        return p.fn.f(d[0], d.length > 1 ? d[1] : 0, d.length > 2 ? d[2] : 0, d.length > 3 ? d[3] : 0);
    }

    /**
     * Which of the 32 values after {@code base} the car supports, from an answer
     * to 0100, 0120, 0140 or 0160. Element i is PID base+1+i. Every ECU that
     * answered is counted. Null when the answer holds no such list.
     */
    public static boolean[] supported(String cleaned, int base) {
        String key = "41" + hex2(base);
        boolean[] out = null;
        for (String line : cleaned.split("\r")) {
            int i = line.indexOf(key);
            if (i < 0) continue;
            long mask = 0;
            boolean ok = true;
            for (int b = 0; b < 4; b++) {
                int v = hexAt(line, i + 4 + 2 * b, 2);
                if (v < 0) { ok = false; break; }
                mask = (mask << 8) | v;
            }
            if (!ok) continue;
            if (out == null) out = new boolean[32];
            for (int bit = 0; bit < 32; bit++) if ((mask & (1L << (31 - bit))) != 0) out[bit] = true;
        }
        return out;
    }

    /** Boost above outside air, kPa. */
    public static double boost(double mapKpa, double baroKpa) {
        if (Double.isNaN(mapKpa)) return Double.NaN;
        return mapKpa - (Double.isNaN(baroKpa) ? 101.3 : baroKpa);
    }

    /**
     * Fuel economy right now in km/L, from the air the engine is breathing.
     * Petrol burns at about 14.7 parts air to 1 part fuel and weighs about 745 g
     * per litre. NaN below 5 km/h, where the figure means nothing.
     */
    public static double economy(double kmh, double mafGs, double lambda, double fuelRateLh) {
        if (Double.isNaN(kmh) || kmh < 5) return Double.NaN;
        double lh = fuelRateLh;
        if (Double.isNaN(lh) || lh <= 0) {
            if (Double.isNaN(mafGs) || mafGs <= 0) return Double.NaN;
            double lam = Double.isNaN(lambda) || lambda < 0.5 || lambda > 1.6 ? 1.0 : lambda;
            lh = mafGs / (14.7 * lam) * 3600 / 745.0;
        }
        return lh <= 0 ? Double.NaN : Math.min(99.9, kmh / lh);
    }

    // ------------------------------------------------------------------ fault codes

    /**
     * Fault codes from an answer to mode 03 (stored), 07 (pending) or 0A (permanent).
     *
     * @param can true on a CAN car (nearly everything since 2008): answers then carry a count byte
     */
    public static List<String> parseDtcs(String cleaned, int mode, boolean can) {
        String resp = hex2(0x40 + mode);
        List<String> messages = new ArrayList<>();
        List<StringBuilder> multi = new ArrayList<>();
        List<Integer> multiNext = new ArrayList<>();
        for (String line : cleaned.split("\r")) {
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon == 1) { // one part of a long answer, e.g. "0:430401710300"
                int idx = Character.digit(line.charAt(0), 16);
                String payload = line.substring(2);
                if (idx == 0) {
                    multi.add(new StringBuilder(payload));
                    multiNext.add(1);
                } else {
                    for (int m = 0; m < multi.size(); m++) {
                        if (multiNext.get(m) == idx) {
                            multi.get(m).append(payload);
                            multiNext.set(m, idx + 1);
                            break;
                        }
                    }
                }
            } else if (line.length() <= 3 && hexAt(line, 0, line.length()) >= 0) {
                // the length line that comes before a long answer
            } else {
                messages.add(line);
            }
        }
        for (StringBuilder sb : multi) messages.add(sb.toString());

        List<String> out = new ArrayList<>();
        for (String msg : messages) {
            int i = msg.indexOf(resp);
            if (i < 0 || i > 8) continue;
            int p = i + 2;
            int limit = Integer.MAX_VALUE;
            if (can) {
                int n = hexAt(msg, p, 2);
                if (n < 0) continue;
                limit = n;
                p += 2;
            }
            for (int c = 0; c < limit; c++) {
                int v = hexAt(msg, p + 4 * c, 4);
                if (v < 0) break;
                if (v == 0) continue;
                String code = dtcName(v);
                if (!out.contains(code)) out.add(code);
            }
        }
        return out;
    }

    /** Two bytes to a code such as P0171. */
    public static String dtcName(int v) {
        char sys = "PCBU".charAt((v >> 14) & 3);
        return String.format(Locale.US, "%c%d%03X", sys, (v >> 12) & 3, v & 0xFFF);
    }

    /** The fault code stored with the freeze frame, from an answer to 020200, or null. */
    public static String parseFreezeDtc(String cleaned) {
        int[] d = bytesAfter(cleaned, "420200", 2);
        if (d == null) return null;
        int v = d[0] * 256 + d[1];
        return v == 0 ? null : dtcName(v);
    }

    /** What the check-engine light and the self-tests are doing, from an answer to 0101. */
    public static final class Readiness {
        public boolean mil;
        public int dtcCount;
        public final List<String> complete = new ArrayList<>();
        public final List<String> incomplete = new ArrayList<>();
    }

    public static Readiness parseReadiness(String cleaned) {
        int[] d = bytesAfter(cleaned, "4101", 4);
        if (d == null) return null;
        Readiness r = new Readiness();
        r.mil = (d[0] & 0x80) != 0;
        r.dtcCount = d[0] & 0x7F;
        String[] always = {"Misfire", "Fuel system", "Components"};
        for (int i = 0; i < 3; i++) {
            if ((d[1] & (1 << i)) == 0) continue;
            ((d[1] & (1 << (i + 4))) != 0 ? r.incomplete : r.complete).add(always[i]);
        }
        boolean diesel = (d[1] & 0x08) != 0;
        String[] tests = diesel
                ? new String[]{"NMHC catalyst", "NOx aftertreatment", null, "Boost pressure", null, "Exhaust gas sensor", "Particulate filter", "EGR / VVT"}
                : new String[]{"Catalyst", "Heated catalyst", "Evaporative system", "Secondary air", "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR / VVT"};
        for (int i = 0; i < 8; i++) {
            if (tests[i] == null || (d[2] & (1 << i)) == 0) continue;
            ((d[3] & (1 << i)) != 0 ? r.incomplete : r.complete).add(tests[i]);
        }
        return r;
    }

    private static final Map<String, String> DTC = new LinkedHashMap<>();

    private static void dtc(String code, String text) {
        DTC.put(code, text);
    }

    static {
        dtc("P0010", "Intake cam timing solenoid circuit, bank 1");
        dtc("P0011", "Intake cam timing over-advanced, bank 1 (AVCS)");
        dtc("P0012", "Intake cam timing over-retarded, bank 1 (AVCS)");
        dtc("P0013", "Exhaust cam timing solenoid circuit, bank 1");
        dtc("P0014", "Exhaust cam timing over-advanced, bank 1");
        dtc("P0016", "Crank and intake cam out of step, bank 1");
        dtc("P0017", "Crank and exhaust cam out of step, bank 1");
        dtc("P0018", "Crank and intake cam out of step, bank 2");
        dtc("P0019", "Crank and exhaust cam out of step, bank 2");
        dtc("P0020", "Intake cam timing solenoid circuit, bank 2");
        dtc("P0021", "Intake cam timing over-advanced, bank 2 (AVCS)");
        dtc("P0022", "Intake cam timing over-retarded, bank 2 (AVCS)");
        dtc("P0030", "Front A/F sensor heater circuit");
        dtc("P0031", "Front A/F sensor heater circuit low");
        dtc("P0032", "Front A/F sensor heater circuit high");
        dtc("P0037", "Rear O2 sensor heater circuit low");
        dtc("P0038", "Rear O2 sensor heater circuit high");
        dtc("P0068", "Manifold pressure and throttle position disagree");
        dtc("P0087", "Fuel rail pressure too low");
        dtc("P0088", "Fuel rail pressure too high");
        dtc("P0101", "Air flow sensor reading out of range");
        dtc("P0102", "Air flow sensor signal low");
        dtc("P0103", "Air flow sensor signal high");
        dtc("P0106", "Manifold pressure sensor out of range");
        dtc("P0107", "Manifold pressure sensor signal low");
        dtc("P0108", "Manifold pressure sensor signal high");
        dtc("P0111", "Intake air temperature sensor out of range");
        dtc("P0112", "Intake air temperature sensor signal low");
        dtc("P0113", "Intake air temperature sensor signal high");
        dtc("P0116", "Coolant temperature sensor out of range");
        dtc("P0117", "Coolant temperature sensor signal low");
        dtc("P0118", "Coolant temperature sensor signal high");
        dtc("P0121", "Throttle position sensor out of range");
        dtc("P0122", "Throttle position sensor signal low");
        dtc("P0123", "Throttle position sensor signal high");
        dtc("P0125", "Engine too cool for closed-loop fuel control");
        dtc("P0126", "Engine too cool for stable running");
        dtc("P0128", "Thermostat: engine not reaching temperature");
        dtc("P0130", "Front A/F sensor circuit");
        dtc("P0131", "Front A/F sensor signal low");
        dtc("P0132", "Front A/F sensor signal high");
        dtc("P0133", "Front A/F sensor slow to respond");
        dtc("P0134", "Front A/F sensor shows no activity");
        dtc("P0136", "Rear O2 sensor circuit");
        dtc("P0137", "Rear O2 sensor signal low");
        dtc("P0138", "Rear O2 sensor signal high");
        dtc("P0139", "Rear O2 sensor slow to respond");
        dtc("P0140", "Rear O2 sensor shows no activity");
        dtc("P0141", "Rear O2 sensor heater");
        dtc("P0171", "Mixture too lean, bank 1");
        dtc("P0172", "Mixture too rich, bank 1");
        dtc("P0174", "Mixture too lean, bank 2");
        dtc("P0175", "Mixture too rich, bank 2");
        dtc("P0191", "Fuel rail pressure sensor out of range");
        dtc("P0201", "Injector circuit, cylinder 1");
        dtc("P0202", "Injector circuit, cylinder 2");
        dtc("P0203", "Injector circuit, cylinder 3");
        dtc("P0204", "Injector circuit, cylinder 4");
        dtc("P0222", "Throttle sensor B signal low");
        dtc("P0223", "Throttle sensor B signal high");
        dtc("P0230", "Fuel pump circuit");
        dtc("P0234", "Turbo overboost");
        dtc("P0235", "Boost pressure sensor circuit");
        dtc("P0244", "Wastegate solenoid out of range");
        dtc("P0245", "Wastegate solenoid circuit low");
        dtc("P0246", "Wastegate solenoid circuit high");
        dtc("P0299", "Turbo underboost");
        dtc("P0300", "Random misfire on several cylinders");
        dtc("P0301", "Misfire, cylinder 1");
        dtc("P0302", "Misfire, cylinder 2");
        dtc("P0303", "Misfire, cylinder 3");
        dtc("P0304", "Misfire, cylinder 4");
        dtc("P0325", "Knock sensor circuit");
        dtc("P0327", "Knock sensor signal low");
        dtc("P0328", "Knock sensor signal high");
        dtc("P0335", "Crank position sensor circuit");
        dtc("P0336", "Crank position sensor out of range");
        dtc("P0340", "Cam position sensor circuit, bank 1");
        dtc("P0341", "Cam position sensor out of range, bank 1");
        dtc("P0345", "Cam position sensor circuit, bank 2");
        dtc("P0351", "Ignition coil circuit, cylinder 1");
        dtc("P0352", "Ignition coil circuit, cylinder 2");
        dtc("P0353", "Ignition coil circuit, cylinder 3");
        dtc("P0354", "Ignition coil circuit, cylinder 4");
        dtc("P0365", "Exhaust cam position sensor circuit, bank 1");
        dtc("P0390", "Exhaust cam position sensor circuit, bank 2");
        dtc("P0400", "EGR flow fault");
        dtc("P0401", "EGR flow too low");
        dtc("P0410", "Secondary air injection fault");
        dtc("P0420", "Catalyst efficiency below threshold, bank 1");
        dtc("P0430", "Catalyst efficiency below threshold, bank 2");
        dtc("P0441", "Evap system purge flow wrong");
        dtc("P0442", "Evap system small leak");
        dtc("P0447", "Evap vent valve circuit open");
        dtc("P0448", "Evap vent valve circuit shorted");
        dtc("P0451", "Fuel tank pressure sensor out of range");
        dtc("P0455", "Evap system large leak");
        dtc("P0456", "Evap system very small leak");
        dtc("P0457", "Evap leak: fuel cap loose or missing");
        dtc("P0458", "Evap purge valve circuit low");
        dtc("P0459", "Evap purge valve circuit high");
        dtc("P0461", "Fuel level sensor out of range");
        dtc("P0462", "Fuel level sensor signal low");
        dtc("P0463", "Fuel level sensor signal high");
        dtc("P0500", "Vehicle speed sensor");
        dtc("P0502", "Vehicle speed sensor signal low");
        dtc("P0506", "Idle speed lower than expected");
        dtc("P0507", "Idle speed higher than expected");
        dtc("P0512", "Starter request circuit");
        dtc("P0562", "System voltage low");
        dtc("P0563", "System voltage high");
        dtc("P0600", "ECU communication link");
        dtc("P0604", "ECU memory (RAM) fault");
        dtc("P0605", "ECU memory (ROM) fault");
        dtc("P0606", "ECU processor fault");
        dtc("P0607", "ECU performance fault");
        dtc("P0638", "Throttle actuator out of range");
        dtc("P0700", "Transmission control system has stored a fault");
        dtc("P0705", "Transmission range (gear position) switch");
        dtc("P0710", "Transmission fluid temperature sensor");
        dtc("P0715", "Transmission input (turbine) speed sensor");
        dtc("P0716", "Transmission input speed sensor out of range");
        dtc("P0720", "Transmission output speed sensor");
        dtc("P0730", "Wrong gear ratio");
        dtc("P0741", "Torque converter clutch not locking");
        dtc("P0743", "Torque converter clutch solenoid circuit");
        dtc("P0746", "Line pressure solenoid performance");
        dtc("P0841", "Transmission fluid pressure sensor out of range");
        dtc("P0851", "Neutral switch input low");
        dtc("P0852", "Neutral switch input high");
        dtc("P0868", "CVT secondary pressure low");
        dtc("P0890", "Transmission control relay circuit low");
        dtc("P0965", "CVT pressure control solenoid performance");
        dtc("P0970", "CVT pressure control solenoid circuit low");
        dtc("P1443", "Evap vent valve stuck (Subaru)");
        dtc("P1518", "Starter switch circuit low (Subaru)");
        dtc("P1602", "Control module programming error (Subaru)");
        dtc("P2004", "Tumble generator valve stuck open, bank 1");
        dtc("P2005", "Tumble generator valve stuck open, bank 2");
        dtc("P2006", "Tumble generator valve stuck closed, bank 1");
        dtc("P2007", "Tumble generator valve stuck closed, bank 2");
        dtc("P2008", "Tumble generator valve circuit open, bank 1");
        dtc("P2009", "Tumble generator valve circuit low, bank 1");
        dtc("P2011", "Tumble generator valve circuit open, bank 2");
        dtc("P2012", "Tumble generator valve circuit low, bank 2");
        dtc("P2016", "Tumble generator valve position sensor low, bank 1");
        dtc("P2017", "Tumble generator valve position sensor high, bank 1");
        dtc("P2021", "Tumble generator valve position sensor low, bank 2");
        dtc("P2022", "Tumble generator valve position sensor high, bank 2");
        dtc("P2088", "Intake cam solenoid circuit low, bank 1");
        dtc("P2089", "Intake cam solenoid circuit high, bank 1");
        dtc("P2096", "Fuel trim after the catalyst too lean");
        dtc("P2097", "Fuel trim after the catalyst too rich");
        dtc("P2101", "Throttle actuator motor out of range");
        dtc("P2102", "Throttle actuator motor circuit low");
        dtc("P2103", "Throttle actuator motor circuit high");
        dtc("P2109", "Throttle position at its minimum stop");
        dtc("P2119", "Throttle body out of range");
        dtc("P2122", "Accelerator pedal sensor D signal low");
        dtc("P2123", "Accelerator pedal sensor D signal high");
        dtc("P2127", "Accelerator pedal sensor E signal low");
        dtc("P2128", "Accelerator pedal sensor E signal high");
        dtc("P2135", "Throttle sensors A and B disagree");
        dtc("P2138", "Accelerator pedal sensors D and E disagree");
        dtc("P2195", "Front A/F sensor stuck lean");
        dtc("P2196", "Front A/F sensor stuck rich");
        dtc("P2227", "Barometric pressure sensor out of range");
        dtc("P2228", "Barometric pressure sensor signal low");
        dtc("P2229", "Barometric pressure sensor signal high");
        dtc("P2270", "Rear O2 sensor stuck lean");
        dtc("P2271", "Rear O2 sensor stuck rich");
        dtc("P2419", "Evap switching valve circuit low");
        dtc("P2420", "Evap switching valve circuit high");
        dtc("P2610", "ECU engine-off timer fault");
        dtc("U0073", "CAN bus off");
        dtc("U0100", "Lost contact with the engine ECU");
        dtc("U0101", "Lost contact with the transmission ECU");
        dtc("U0121", "Lost contact with the ABS/VDC unit");
        dtc("U0122", "Lost contact with the vehicle dynamics unit");
        dtc("U0140", "Lost contact with the body control unit");
        dtc("U0155", "Lost contact with the instrument cluster");
    }

    /** A plain description of a fault code. Unknown codes get the name of their group. */
    public static String dtcText(String code) {
        String t = DTC.get(code);
        if (t != null) return t;
        if (code == null || code.length() != 5) return "Unknown code";
        char sys = code.charAt(0);
        if (sys == 'U') return "Network communication fault";
        if (sys == 'C') return "Chassis fault (brakes, steering or suspension)";
        if (sys == 'B') return "Body fault (airbags, climate, lighting)";
        if (code.charAt(1) == '1' || code.charAt(1) == '3') return "Maker-specific powertrain code. Look it up for Subaru.";
        switch (code.charAt(2)) {
            case '0': return "Fuel, air or emission control fault";
            case '1': case '2': return "Fuel and air metering fault";
            case '3': return "Ignition system or misfire fault";
            case '4': return "Emission control fault";
            case '5': return "Speed, idle or auxiliary input fault";
            case '6': return "ECU or output circuit fault";
            default: return "Transmission fault";
        }
    }
}
