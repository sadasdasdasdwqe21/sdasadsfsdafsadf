package app.shakefinder;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Recording tests: run log, shake-against-rpm map, WAV file. */
public class Tests3 {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.printf("%s %-58s %s%n", ok ? "PASS" : "FAIL", name, detail);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("sf").toFile();

        // A slow rev sweep 1000 -> 2000 rpm with a part that resonates at 1450 rpm.
        Sim s = new Sim(31, 416);
        Sim.Rpm r = t -> 1000 + 25 * t;
        Sim.Amp mod = (o, rpm, t) -> o == 2 ? 1 + 4.0 / (1 + Math.pow((rpm - 1450) / 60.0, 2)) : 1;
        s.generate(40, r, new double[]{1, 2, 4}, new double[]{1.5, 5, 1.5}, mod, null, null, 0);
        Recorder rec = new Recorder();
        File csv = new File(dir, "run.csv");
        rec.start(csv, new String[]{"coolant_C", "load_%"});
        RpmTracker tr = new RpmTracker();
        int mapped = 0;
        for (double t = 3; t <= 39.9; t += 0.25) {
            Sim.Frame f = s.frame(tr, t, null);
            double rpm = f.est == null ? Double.NaN : f.est.rpm;
            double[] mg = new double[4];
            if (!Double.isNaN(rpm)) for (int i = 0; i < 4; i++) mg[i] = Analysis.ampNear(f.fast, SweepMap.ORDERS[i] * rpm / 60, 0.02) / Sim.G * 1000;
            Analysis.Peak top = f.pf == null || f.pf.isEmpty() ? null : f.pf.get(0);
            rec.row(t, rpm, "vibration", 0, "gps", top == null ? Double.NaN : top.hz, top == null ? Double.NaN : top.amp / Sim.G * 1000,
                    "2E", mg, Double.NaN, Double.NaN, -31.5, new double[]{88, Double.NaN}, !Double.isNaN(rpm));
            if (!Double.isNaN(rpm)) mapped++;
        }
        rec.stop();
        int worst = rec.sweep.worstBin(2);
        double worstRpm = worst < 0 ? -1 : SweepMap.rpmOf(worst);
        check("sweep map finds the 1450 rpm resonance", Math.abs(worstRpm - 1450) <= 50 && rec.sweep.mg(2, worst) > 17,
                String.format("worst at %.0f rpm, %.1f mg (true peak 25 mg at 1450); %d slots, %d readings", worstRpm, worst < 0 ? 0 : rec.sweep.mg(2, worst), rec.sweep.covered(), mapped));
        check("sweep map covers the swept range", SweepMap.rpmOf(rec.sweep.firstBin()) < 1120 && SweepMap.rpmOf(rec.sweep.lastBin()) > 1900 && rec.sweep.covered() >= 16,
                String.format("%.0f to %.0f rpm", SweepMap.rpmOf(rec.sweep.firstBin()), SweepMap.rpmOf(rec.sweep.lastBin())));
        check("worst-spot sentence", rec.sweep.worstText(2).startsWith("Strongest at 1,4"), rec.sweep.worstText(2));
        check("run summary", rec.rows() == 148 && rec.rpmMin() < 1100 && rec.rpmMax() > 1900 && Math.abs(rec.maxRpm() - 1450) < 60 && Math.abs(rec.seconds() - 36.75) < 0.01,
                String.format("%d rows, %.1f s, rpm %.0f-%.0f, strongest %.1f mg at %.0f rpm", rec.rows(), rec.seconds(), rec.rpmMin(), rec.rpmMax(), rec.maxMg(), rec.maxRpm()));

        List<String> lines = Files.readAllLines(csv.toPath());
        int cols = lines.get(0).split(",", -1).length;
        boolean even = true;
        for (String l : lines) if (l.split(",", -1).length != cols) even = false;
        check("log file is a clean table", lines.size() == 149 && cols == 17 && even && lines.get(0).endsWith("sound_dbfs,coolant_C,load_%"),
                lines.size() + " lines, " + cols + " columns");
        String[] c1 = lines.get(1).split(",", -1);
        check("log row values", c1[0].equals("0.00") && c1[2].equals("vibration") && c1[14].equals("-31.5") && c1[15].equals("88.00") && c1[16].isEmpty(),
                lines.get(1));

        // Save and restore the map.
        float[][] sl = new float[SweepMap.SERIES][];
        for (int i = 0; i < SweepMap.SERIES; i++) sl[i] = rec.sweep.slice(i);
        SweepMap back = SweepMap.restore(rec.sweep.firstBin(), sl);
        check("map survives saving", back.worstBin(2) == worst && Math.abs(back.mg(2, worst) - rec.sweep.mg(2, worst)) < 1e-6 && back.covered() == rec.sweep.covered(), "");

        // Tone strength helper.
        Sim q = new Sim(32, 416);
        q.generate(8, t -> 1500, new double[]{2}, new double[]{12}, null, null, null, 0);
        Sim.Frame fq = q.frame(new RpmTracker(), 7.9, null);
        double at = Analysis.ampNear(fq.fast, 50.0, 0.02) / Sim.G * 1000, off = Analysis.ampNear(fq.fast, 75.0, 0.02);
        check("strength at an order, and nothing where nothing is", Math.abs(at - 12) < 0.5 && off == 0, String.format("%.2f mg at 50 Hz, %.2f at 75 Hz", at, off));

        // WAV file.
        File wav = new File(dir, "run.wav");
        WavWriter w = new WavWriter(wav, 44100, 1);
        short[] buf = new short[2048];
        for (int i = 0; i < buf.length; i++) buf[i] = (short) (i - 1024);
        for (int i = 0; i < 30; i++) w.write(buf, buf.length); // 61440 samples asked, 44100 allowed
        w.close();
        byte[] b = Files.readAllBytes(wav.toPath());
        int rate = (b[24] & 255) | (b[25] & 255) << 8 | (b[26] & 255) << 16;
        int data = (b[40] & 255) | (b[41] & 255) << 8 | (b[42] & 255) << 16 | (b[43] & 255) << 24;
        int riff = (b[4] & 255) | (b[5] & 255) << 8 | (b[6] & 255) << 16 | (b[7] & 255) << 24;
        short first = (short) ((b[44] & 255) | (b[45] << 8));
        check("sound file is a valid WAV and stops at its limit", new String(b, 0, 4).equals("RIFF") && new String(b, 8, 4).equals("WAVE") && rate == 44100
                        && data == 88200 && b.length == 44 + 88200 && riff == 36 + 88200 && first == -1024 && w.isFull(),
                String.format("%d Hz, %d data bytes, file %d bytes", rate, data, b.length));

        System.out.println(fails == 0 ? "\nALL PASS" : "\nFAILED: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
