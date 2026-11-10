package app.shakefinder;

import java.util.*;

public class Tests {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.printf("%s %-58s %s%n", ok ? "PASS" : "FAIL", name, detail);
        if (!ok) fails++;
    }
    static String peaksStr(List<Analysis.Peak> ps, int k) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(k, ps.size()); i++) sb.append(String.format("%.2fHz/%.1fmg ", ps.get(i).hz, ps.get(i).amp / Sim.G * 1000));
        return sb.toString();
    }
    static final double[] HIS_ORD = {0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4};
    static final double[] HIS_MG  = {2, 6, 7, 21, 3, 4, 3, 5};

    public static void main(String[] a) {
        // 1. Idle 702 rpm, clean firing series
        for (double fs : new double[]{416, 500, 200, 104}) {
            Sim s = new Sim(1, fs);
            s.generate(8, t -> 702, new double[]{2, 4, 6}, new double[]{30, 9, 3}, null, null, null, 0);
            Sim.Frame f = s.frame(new RpmTracker(), 7.9, null);
            check("idle 702 @" + (int) fs + " Hz", f.est != null && Math.abs(f.rpm - 702) < 2.5,
                    f.est == null ? "null" : String.format("rpm=%.1f fast=%.1f cov=%.2f conf=%.2f amb=%b", f.rpm, f.est.rpm, f.est.coverage, f.est.confidence, f.est.ambiguous));
        }
        // 2. His reverse test, 1572 rpm with half orders
        {
            Sim s = new Sim(2, 416);
            s.generate(8, t -> 1572, HIS_ORD, HIS_MG, null, null, null, 0);
            Sim.Frame f = s.frame(new RpmTracker(), 7.9, null);
            check("reverse test 1572 with half orders", f.est != null && Math.abs(f.rpm - 1572) < 3,
                    f.est == null ? "null" : String.format("rpm=%.1f amb=%b alts=%s conf=%.2f", f.rpm, f.est.ambiguous, f.est.alternatives, f.est.confidence));
            System.out.println("     slow peaks: " + peaksStr(f.ps, 8));
        }
        // 3. Smooth engine, single weak 2E
        {
            Sim s = new Sim(3, 416); s.noiseMg = 1.0;
            s.generate(8, t -> 800, new double[]{2}, new double[]{3}, null, null, null, 0);
            Sim.Frame f = s.frame(new RpmTracker(), 7.9, null);
            check("smooth engine, lone 3 mg 2E at 800", f.est != null && Math.abs(f.rpm - 800) < 3,
                    f.est == null ? "null" : String.format("rpm=%.1f conf=%.2f amb=%b", f.rpm, f.est.confidence, f.est.ambiguous));
        }
        // 4. Driving: 1500 rpm, 60 km/h tyre + propshaft peaks, road noise
        {
            double circ = Orders.tyreCircumferenceM("225/55R18");
            double tyre = 60 / 3.6 / circ, prop = tyre * 3.7;
            Sim s = new Sim(4, 416);
            s.generate(10, t -> 1500, new double[]{1, 2, 4}, new double[]{2, 10, 3}, null,
                    new double[]{tyre, prop}, new double[]{15, 12}, 6);
            double[] ex = Orders.roadFrequencies(60, "225/55R18", 3.7);
            Sim.Frame f = s.frame(new RpmTracker(), 9.9, ex);
            check("driving 1500 rpm, tyre+prop louder, GPS speed known", f.est != null && Math.abs(f.rpm - 1500) < 4,
                    f.est == null ? "null" : String.format("rpm=%.1f cov=%.2f conf=%.2f", f.rpm, f.est.coverage, f.est.confidence));
            Sim.Frame g = s.frame(new RpmTracker(), 9.9, null);
            System.out.println("     same without GPS speed: " + (g.est == null ? "no reading" : String.format("rpm=%.1f", g.rpm))
                    + "   tyre=" + String.format("%.2f", tyre) + " prop=" + String.format("%.2f", prop));
            System.out.println("     slow peaks: " + peaksStr(f.ps, 8));
        }
        // 5. Jump from idle 700 to 1400 with half orders (the trap case)
        {
            Sim s = new Sim(5, 416);
            Sim.Rpm r = t -> t < 6 ? 700 : t < 7 ? 700 + 700 * (t - 6) : 1400;
            Sim.Amp mod = (o, rpm, t) -> {
                // idle: clean firing series. after: his car's half-order pattern
                boolean after = t >= 6.5;
                if (!after) return o == 2 ? 30.0 / 21 : o == 4 ? 9.0 / 5 : 0;
                return 1;
            };
            s.generate(16, r, HIS_ORD, HIS_MG, mod, null, null, 0);
            RpmTracker tr = new RpmTracker();
            double settle = Double.NaN; StringBuilder tl = new StringBuilder();
            boolean okBefore = true;
            for (double t = 3; t <= 15.9; t += 0.25) {
                Sim.Frame f = s.frame(tr, t, null);
                double v = f.rpm;
                if (t < 6 && !(Math.abs(v - 700) < 4)) okBefore = false;
                if (t >= 7 && Double.isNaN(settle) && Math.abs(v - 1400) < 10) settle = t - 7;
                if (t >= 7 && !Double.isNaN(settle) && !(Math.abs(v - 1400) < 10)) settle = Double.NaN;
                if (t >= 5.5 && t <= 11) tl.append(String.format("%.2f:%s ", t, Double.isNaN(v) ? "--" : String.format("%.0f", v)));
            }
            check("jump 700 -> 1400: right before, settles after", okBefore && !Double.isNaN(settle) && settle <= 3.0,
                    String.format("settled %.2f s after the rev finished", settle));
            System.out.println("     " + tl);
        }
        // 6. Slow sweep 1200 -> 1800 over 15 s
        {
            Sim s = new Sim(6, 416);
            Sim.Rpm r = t -> 1200 + 40 * t;
            s.generate(15, r, HIS_ORD, HIS_MG, null, null, null, 0);
            RpmTracker tr = new RpmTracker();
            double worst = 0; int nulls = 0, cnt = 0;
            for (double t = 3; t <= 14.9; t += 0.25) {
                Sim.Frame f = s.frame(tr, t, null);
                cnt++;
                if (f.est == null) { nulls++; continue; }
                // the fast window reports the rpm at about its middle
                double truth = r.at(t - Sim.FAST_WIN / 2);
                worst = Math.max(worst, Math.abs(f.rpm - truth));
            }
            check("sweep 1200 -> 1800 at 40 rpm/s", worst < 30 && nulls <= cnt / 5, String.format("worst error %.0f rpm, %d/%d frames unread", worst, nulls, cnt));
        }
        // 7. Amplitude accuracy with jitter, tilted direction
        for (double hz : new double[]{23.37, 47.3, 99.6, 151.2}) {
            Sim s = new Sim(7, 416);
            s.noiseMg = 0.5;
            s.generate(8, t -> 0, new double[]{}, new double[]{}, null, new double[]{hz}, new double[]{20}, 0);
            Sim.Frame f = s.frame(new RpmTracker(), 7.9, null);
            Analysis.Peak p = f.ps.get(0);
            double mg = p.amp / Sim.G * 1000;
            check(String.format("20 mg tone at %.1f Hz reads right", hz), Math.abs(mg - 20) < 0.6 && Math.abs(p.hz - hz) < 0.03,
                    String.format("%.3f Hz, %.2f mg, vert %.0f%%", p.hz, mg, p.vertFrac * 100));
        }
        // 7b. Same with dropped samples (the fill-in path)
        for (double hz : new double[]{47.3, 99.6}) {
            Sim s = new Sim(71, 416);
            s.noiseMg = 0.5; s.dropProb = 0.004;
            s.generate(8, t -> 0, new double[]{}, new double[]{}, null, new double[]{hz}, new double[]{20}, 0);
            Sim.Frame f = s.frame(new RpmTracker(), 7.9, null);
            Analysis.Peak p = f.ps.get(0);
            double mg = p.amp / Sim.G * 1000;
            check(String.format("20 mg tone at %.1f Hz with dropped samples", hz), Math.abs(mg - 20) < 1.0 && Math.abs(p.hz - hz) < 0.05,
                    String.format("%.3f Hz, %.2f mg, %d drops filled", p.hz, mg, f.slow.gaps));
        }
        {
            Sim s = new Sim(72, 416); s.dropProb = 0.004;
            s.generate(8, t -> 1572, HIS_ORD, HIS_MG, null, null, null, 0);
            Sim.Frame f = s.frame(new RpmTracker(), 7.9, null);
            check("reverse test 1572 with dropped samples", f.est != null && Math.abs(f.rpm - 1572) < 3,
                    f.est == null ? "null" : String.format("rpm=%.1f gaps=%d", f.rpm, f.slow.gaps));
        }
        // 8. Engine off: noise only
        {
            Sim s = new Sim(8, 416);
            s.generate(8, t -> 0, new double[]{}, new double[]{}, null, null, null, 0);
            Sim.Frame f = s.frame(new RpmTracker(), 7.9, null);
            check("engine off gives no reading", f.est == null && f.ps.size() <= 1, "peaks=" + f.ps.size() + " " + peaksStr(f.ps, 3));
            Sim s2 = new Sim(81, 416);
            s2.generate(10, t -> 0, new double[]{}, new double[]{}, null, null, null, 8);
            RpmTracker tr = new RpmTracker(); int reads = 0;
            for (double t = 5.5; t < 9.9; t += 0.25) if (s2.frame(tr, t, null).est != null) reads++;
            check("coasting, road noise only: few false readings", reads <= 3, reads + "/18 frames gave an rpm");
        }
        // 9. 4E resonance: truth 786, auto prefers 1572, lock fixes it and follows
        {
            Sim s = new Sim(9, 416);
            Sim.Rpm r = t -> t < 8 ? 786 : 786 + 15 * (t - 8);
            s.generate(16, r, new double[]{2, 4, 6, 8}, new double[]{6, 21, 4, 5}, null, null, null, 0);
            RpmTracker tr = new RpmTracker();
            Sim.Frame f = s.frame(tr, 7.0, null);
            boolean offered = f.est != null && f.est.alternatives.size() > 0 && Math.abs(f.est.alternatives.get(0) - 786) < 6;
            check("4E resonance: other octave is offered", offered, f.est == null ? "null" : String.format("auto=%.0f alts=%s amb=%b", f.rpm, f.est.alternatives, f.est.ambiguous));
            tr.lock(f.est.alternatives.get(0), 7.0);
            boolean ok = true; double last = 0;
            for (double t = 7.25; t <= 15.9; t += 0.25) {
                Sim.Frame g = s.frame(tr, t, null);
                double truth = r.at(t - 1);
                if (g.est == null || Math.abs(g.rpm - truth) > 25 || !g.est.locked) ok = false;
                last = g.rpm;
            }
            check("lock holds and follows 786 -> 906", ok, String.format("ended at %.0f", last));
        }
        // 10. Precision over random rpm
        {
            Random rr = new Random(10); double worst = 0, sum = 0; int nn = 60, bad = 0;
            for (int i = 0; i < nn; i++) {
                double rpm = 620 + rr.nextDouble() * 2900;
                Sim s = new Sim(100 + i, i % 2 == 0 ? 416 : 500);
                s.generate(7, t -> rpm, new double[]{1, 2, 4, 6}, new double[]{1 + rr.nextDouble() * 2, 8 + rr.nextDouble() * 20, 2 + rr.nextDouble() * 5, 1}, null, null, null, 0);
                Sim.Frame f = s.frame(new RpmTracker(), 6.9, null);
                if (f.est == null) { bad++; continue; }
                double e = Math.abs(f.rpm - rpm);
                if (e > 20) bad++; else { worst = Math.max(worst, e); sum += e; }
            }
            check("60 random steady speeds 620-3520 rpm", bad == 0 && worst < 3, String.format("mean error %.2f rpm, worst %.2f, misses %d", sum / (nn - bad), worst, bad));
        }
        // 11. Direction
        {
            Sim s = new Sim(11, 416); s.dir = new double[]{0, 0, 1}; s.grav = new double[]{0, 0, 9.81};
            s.generate(8, t -> 900, new double[]{2}, new double[]{15}, null, null, null, 0);
            Analysis.Peak p = s.frame(new RpmTracker(), 7.9, null).ps.get(0);
            Sim s2 = new Sim(12, 416); s2.dir = new double[]{1, 0, 0}; s2.grav = new double[]{0, 0, 9.81};
            s2.generate(8, t -> 900, new double[]{2}, new double[]{15}, null, null, null, 0);
            Analysis.Peak p2 = s2.frame(new RpmTracker(), 7.9, null).ps.get(0);
            check("up-down share: vertical shake vs sideways shake", p.vertFrac > 0.95 && p2.vertFrac < 0.05, String.format("%.2f vs %.2f", p.vertFrac, p2.vertFrac));
        }
        // 12. Orders and speed filter
        {
            double c = Orders.tyreCircumferenceM("225/55R18");
            check("225/55R18 rolls about 2.147 m per turn", Math.abs(c - 2.1475) < 0.003, String.format("%.4f m, %.0f revs/km", c, 1000 / c));
            List<Orders.Source> src = Orders.compute(1572, 60, "225/55R18", 3.7, 0.012, 0.035);
            Orders.Source m = Orders.bestMatch(src, 52.4, 0.39);
            check("52.4 Hz at 1572 rpm is engine 2nd order", m != null && m.shortName.equals("2E"), m == null ? "null" : m.name);
            Orders.Source none = Orders.bestMatch(src, 55.0, 0.39);
            check("55.0 Hz at 1572 rpm matches nothing", none == null, none == null ? "none" : none.name);
            SpeedFilter sf = new SpeedFilter();
            for (int i = 0; i < 8; i++) sf.add(i, 16.6 + (i == 5 ? 3 : 0), 0.3);
            check("speed filter ignores one wild fix", Math.abs(sf.kmh(7.2) - 59.76) < 0.1, String.format("%.2f km/h", sf.kmh(7.2)));
            check("speed filter goes stale after 3.5 s", Double.isNaN(sf.kmh(11.0)) && !sf.hasFix(11.0), "");
            SpeedFilter st = new SpeedFilter(); st.add(0, 0.2, 0.3); st.add(1, 0.3, 0.3);
            check("parked car reads 0 km/h", st.kmh(1.1) == 0, "");
        }
        System.out.println(fails == 0 ? "\nALL PASS" : "\nFAILED: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
