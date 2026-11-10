package app.shakefinder;

import java.util.*;

/** Sound, fusion and OBD decoding tests. */
public class Tests2 {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.printf("%s %-58s %s%n", ok ? "PASS" : "FAIL", name, detail);
        if (!ok) fails++;
    }

    /** Cabin engine sound: firing harmonics through a phone mic that rolls off below 80 Hz, plus hiss and rumble. */
    static SoundRpm sound(long seed, int sr, double seconds, Sim.Rpm rpm, double[] extraHz, double[] extraAmp, double level, double noise) {
        Random rnd = new Random(seed);
        SoundRpm s = new SoundRpm(sr);
        short[] buf = new short[2048];
        int n = (int) (seconds * sr), filled = 0;
        double ang = 0, lp = 0;
        double[] ph = new double[16];
        for (int i = 0; i < ph.length; i++) ph[i] = rnd.nextDouble() * 6.283;
        for (int i = 0; i < n; i++) {
            double t = i / (double) sr;
            double fire = rpm.at(t) / 30.0;
            ang += fire / sr;
            double v = 0;
            if (fire > 0) for (int k = 1; k <= 14; k++) {
                double f = k * fire;
                if (f > 1500) break;
                double mic = (f / 80) / Math.sqrt(1 + (f / 80) * (f / 80));
                v += level / Math.pow(k, 0.8) * mic * Math.sin(2 * Math.PI * k * ang + ph[k]);
            }
            if (extraHz != null) for (int e = 0; e < extraHz.length; e++) v += extraAmp[e] * Math.sin(2 * Math.PI * extraHz[e] * t);
            double w = rnd.nextGaussian();
            lp += (w - lp) * 0.02; // low rumble
            v += noise * (w * 0.5 + lp * 12);
            buf[filled++] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
            if (filled == buf.length) { s.feed(buf, filled); filled = 0; }
        }
        s.feed(buf, filled);
        return s;
    }

    public static void main(String[] a) {
        // ---- sound alone
        for (int sr : new int[]{44100, 48000}) for (double rpm : new double[]{702, 1450, 2380, 3600}) {
            SoundRpm s = sound(1, sr, 3, t -> rpm, null, null, 1500, 250);
            SoundRpm.Sound sp = s.analyze();
            List<Analysis.Peak> pk = SoundRpm.peaks(sp, 16);
            RpmTracker.Estimate e = new RpmTracker(RpmTracker.Kind.SOUND).update(pk, sp.df, null, 0, 3, null);
            check(String.format("engine sound at %.0f rpm, mic %d Hz", rpm, sr), e != null && Math.abs(e.rpm - rpm) < rpm * 0.004,
                    e == null ? "null (" + pk.size() + " peaks)" : String.format("rpm=%.1f matched=%d conf=%.2f", e.rpm, e.matched, e.confidence));
        }
        {
            SoundRpm s = sound(2, 44100, 3, t -> 0, null, null, 0, 250);
            SoundRpm.Sound sp = s.analyze();
            List<Analysis.Peak> pk = SoundRpm.peaks(sp, 16);
            RpmTracker.Estimate e = new RpmTracker(RpmTracker.Kind.SOUND).update(pk, sp.df, null, 0, 3, null);
            check("cabin noise only: sound gives no rpm", e == null, pk.size() + " peaks, level " + String.format("%.0f dBFS", sp.levelDb));
        }
        {
            // level check: a 1000-count tone at 200 Hz should read 20*log10(1000/32768) = -30.3 dBFS
            SoundRpm s = sound(3, 44100, 3, t -> 0, new double[]{200}, new double[]{1000}, 0, 5);
            SoundRpm.Sound sp = s.analyze();
            int k = (int) Math.round(200 / sp.df); double best = -999;
            for (int q = k - 2; q <= k + 2; q++) best = Math.max(best, sp.db(q));
            check("sound level of a known tone", Math.abs(best - (-30.3)) < 1.6, String.format("%.1f dBFS (true -30.3)", best));
            SoundRpm s2 = sound(3, 44100, 3, t -> 0, new double[]{600}, new double[]{1000}, 0, 5);
            SoundRpm.Sound sp2 = s2.analyze();
            k = (int) Math.round(600 / sp2.df); best = -999;
            for (int q = k - 2; q <= k + 2; q++) best = Math.max(best, sp2.db(q));
            check("sound level holds at 600 Hz", Math.abs(best - (-30.3)) < 1.6, String.format("%.1f dBFS", best));
        }
        // ---- sound backing up vibration
        {
            // 4E structural resonance: truth 786 rpm. Vibration alone prefers 1572; sound hears 786.
            Sim v = new Sim(9, 416);
            v.generate(8, t -> 786, new double[]{2, 4, 6, 8}, new double[]{6, 21, 4, 5}, null, null, null, 0);
            SoundRpm s = sound(4, 44100, 3, t -> 786, null, null, 1500, 250);
            SoundRpm.Sound sp = s.analyze();
            RpmTracker.Estimate se = new RpmTracker(RpmTracker.Kind.SOUND).update(SoundRpm.peaks(sp, 16), sp.df, null, 0, 7.9, null);
            Sim.Frame alone = v.frame(new RpmTracker(), 7.9, null);
            long[] st = new long[v.n]; float[] sx = new float[v.n], sy = new float[v.n], sz = new float[v.n];
            int c = v.snap(7.9, Sim.FAST_WIN, st, sx, sy, sz);
            Analysis.Spectrum fast = Analysis.welch(st, sx, sy, sz, c, Sim.FAST_SEG);
            List<Analysis.Peak> pf = Analysis.peaks(fast, 4, 187, 14, 0.006);
            double[][] support = se == null ? null : new double[][]{{se.rpm, se.confidence}};
            RpmTracker.Estimate fused = new RpmTracker().update(pf, fast.df, null, 0, 7.9, support);
            check("resonance trap: sound corrects the vibration reading", se != null && fused != null && Math.abs(fused.rpm - 786) < 4 && Math.abs(alone.rpm - 1572) < 8,
                    String.format("vibration alone %.0f, sound %.0f, together %.0f", alone.rpm, se == null ? -1 : se.rpm, fused == null ? -1 : fused.rpm));
        }
        {
            // his reverse test: vibration is ambiguous (1572 vs 786); sound at 1572 settles it
            Sim v = new Sim(2, 416);
            v.generate(8, t -> 1572, Tests.HIS_ORD, Tests.HIS_MG, null, null, null, 0);
            SoundRpm s = sound(5, 44100, 3, t -> 1572, null, null, 1500, 250);
            SoundRpm.Sound sp = s.analyze();
            RpmTracker.Estimate se = new RpmTracker(RpmTracker.Kind.SOUND).update(SoundRpm.peaks(sp, 16), sp.df, null, 0, 7.9, null);
            long[] st = new long[v.n]; float[] sx = new float[v.n], sy = new float[v.n], sz = new float[v.n];
            int c = v.snap(7.9, Sim.FAST_WIN, st, sx, sy, sz);
            Analysis.Spectrum fast = Analysis.welch(st, sx, sy, sz, c, Sim.FAST_SEG);
            RpmTracker.Estimate fused = new RpmTracker().update(Analysis.peaks(fast, 4, 187, 14, 0.006), fast.df, null, 0, 7.9,
                    se == null ? null : new double[][]{{se.rpm, se.confidence}});
            check("reverse test: sound removes the doubt", fused != null && Math.abs(fused.rpm - 1572) < 4 && !fused.ambiguous,
                    fused == null ? "null" : String.format("rpm=%.0f ambiguous=%b conf=%.2f", fused.rpm, fused.ambiguous, fused.confidence));
        }
        // ---- OBD answers
        {
            String r = Elm327.clean("41 0C 1A F8 \r\r>");
            check("rpm answer", Elm327.parseRpm(r) == 1726.0, "" + Elm327.parseRpm(r));
            check("rpm, two ECUs answer", Elm327.parseRpm(Elm327.clean("41 0C 0B 0C\r41 0C 0B 0C\r\r>")) == 707.0, "");
            check("rpm with headers on", Elm327.parseRpm(Elm327.clean("7E8 04 41 0C 1A F8\r>")) == 1726.0, "");
            check("rpm while searching", Elm327.parseRpm(Elm327.clean("SEARCHING...\r41 0C 0A F0\r\r>")) == 700.0, "");
            check("speed answer", Elm327.parseSpeed(Elm327.clean("41 0D 3C\r>")) == 60.0, "");
            check("no data is not a number", Double.isNaN(Elm327.parseRpm(Elm327.clean("NO DATA\r>"))) && Elm327.noData(Elm327.clean("NO DATA\r>")), "");
            check("unknown command spotted", Elm327.rejected(Elm327.clean("?\r>")), "");
            boolean[] sup = Elm327.supported(Elm327.clean("41 00 BE 1F A8 13\r41 00 80 00 00 01\r>"), 0);
            check("supported list", sup != null && sup[0x0C - 1] && sup[0x0D - 1] && sup[0x04 - 1] && !sup[0x02 - 1] && sup[0x20 - 1] && sup[0x11 - 1],
                    sup == null ? "null" : "0C,0D,04,11 yes; 02 no; next page yes");
            check("coolant 83 C", Elm327.parse(Elm327.clean("41 05 7B"), 1, Elm327.pid(0x05)) == 83, "");
            check("long trim -10.9 %", Math.abs(Elm327.parse(Elm327.clean("41 07 72"), 1, Elm327.pid(0x07)) - (-10.94)) < 0.01, "" + Elm327.parse(Elm327.clean("41 07 72"), 1, Elm327.pid(0x07)));
            check("lambda 1.000", Math.abs(Elm327.parse(Elm327.clean("41 34 80 00 80 00"), 1, Elm327.pid(0x34)) - 1.0) < 1e-9, "");
            check("ECU voltage 14.1", Math.abs(Elm327.parse(Elm327.clean("41 42 37 14"), 1, Elm327.pid(0x42)) - 14.1) < 0.001, "");
            check("freeze frame coolant", Elm327.parse(Elm327.clean("42 05 00 7B"), 2, Elm327.pid(0x05)) == 83, "");
            check("freeze frame code", "P0171".equals(Elm327.parseFreezeDtc(Elm327.clean("42 02 00 01 71"))), "" + Elm327.parseFreezeDtc(Elm327.clean("42 02 00 01 71")));
            check("codes, CAN single line", Elm327.parseDtcs(Elm327.clean("43 02 01 71 03 00\r>"), 3, true).equals(Arrays.asList("P0171", "P0300")), "" + Elm327.parseDtcs(Elm327.clean("43 02 01 71 03 00\r>"), 3, true));
            List<String> multi = Elm327.parseDtcs(Elm327.clean("00A\r0: 43 04 01 71 03 00\r1: 03 01 20 04 00 00 00\r\r>"), 3, true);
            check("codes, CAN long answer", multi.equals(Arrays.asList("P0171", "P0300", "P0301", "P2004")), "" + multi);
            List<String> two = Elm327.parseDtcs(Elm327.clean("43 01 01 72\r43 01 07 00\r>"), 3, true);
            check("codes from two ECUs", two.equals(Arrays.asList("P0172", "P0700")), "" + two);
            check("codes, older non-CAN car", Elm327.parseDtcs(Elm327.clean("43 01 71 03 00 00 00\r43 03 01 00 00 00 00\r>"), 3, false).equals(Arrays.asList("P0171", "P0300", "P0301")), "");
            check("no codes", Elm327.parseDtcs(Elm327.clean("43 00\r>"), 3, true).isEmpty() && Elm327.parseDtcs(Elm327.clean("NO DATA\r>"), 3, true).isEmpty(), "");
            check("pending codes use 47", Elm327.parseDtcs(Elm327.clean("47 01 C1 00\r>"), 7, true).equals(Arrays.asList("U0100")), "" + Elm327.parseDtcs(Elm327.clean("47 01 C1 00\r>"), 7, true));
            check("code descriptions", Elm327.dtcText("P0172").contains("rich") && Elm327.dtcText("P2004").contains("Tumble") && Elm327.dtcText("P0999").contains("Transmission") && Elm327.dtcText("P1234").contains("Maker"), "");
            Elm327.Readiness rd = Elm327.parseReadiness(Elm327.clean("41 01 83 07 65 04\r>"));
            check("readiness", rd != null && rd.mil && rd.dtcCount == 3 && rd.complete.contains("Catalyst") && rd.incomplete.contains("Evaporative system") && rd.complete.contains("Misfire"),
                    rd == null ? "null" : "MIL " + rd.mil + ", " + rd.dtcCount + " codes, done " + rd.complete + ", not done " + rd.incomplete);
            double eco = Elm327.economy(60, 8.0, 1.0, Double.NaN);
            check("economy 60 km/h at 8 g/s air", Math.abs(eco - 22.8) < 0.2, String.format("%.1f km/L", eco));
            check("boost", Elm327.boost(165, 88) == 77 && Double.isNaN(Elm327.boost(Double.NaN, 88)), "");
            TimeSeries ts = new TimeSeries(8);
            for (int i = 0; i < 12; i++) ts.add(i * 0.2, 1400 + i);
            check("time series mean and staleness", Math.abs(ts.mean(2.2, 1.0) - 1408.5) < 0.01 && Double.isNaN(ts.latest(5, 1.5)) && ts.latest(2.3, 1.5) == 1411, "" + ts.mean(2.2, 1.0));
        }
        System.out.println(fails == 0 ? "\nALL PASS" : "\nFAILED: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
