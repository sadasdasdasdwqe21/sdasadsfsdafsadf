package app.shakefinder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Writes 16-bit mono sound to a WAV file. The header needs the final length,
 * so it is written as a placeholder first and filled in when the file closes.
 *
 * Plain Java, no Android imports.
 */
public final class WavWriter {

    private final RandomAccessFile file;
    private final int sampleRate;
    private final long maxBytes;
    private long dataBytes = 0;
    private byte[] scratch = new byte[4096];
    private boolean closed = false;

    /** @param maxSeconds recording stops quietly at this length so a forgotten recording can't fill the phone */
    public WavWriter(File f, int sampleRate, int maxSeconds) throws IOException {
        this.sampleRate = sampleRate;
        this.maxBytes = (long) maxSeconds * sampleRate * 2;
        file = new RandomAccessFile(f, "rw");
        file.setLength(0);
        file.write(header(0));
    }

    public boolean isFull() {
        return dataBytes >= maxBytes;
    }

    public double seconds() {
        return dataBytes / 2.0 / sampleRate;
    }

    public void write(short[] pcm, int n) throws IOException {
        if (closed || n <= 0) return;
        long room = (maxBytes - dataBytes) / 2;
        if (room <= 0) return;
        if (n > room) n = (int) room;
        if (scratch.length < n * 2) scratch = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            scratch[2 * i] = (byte) (pcm[i] & 0xFF);
            scratch[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        file.write(scratch, 0, n * 2);
        dataBytes += n * 2L;
    }

    public void close() throws IOException {
        if (closed) return;
        closed = true;
        file.seek(0);
        file.write(header(dataBytes));
        file.close();
    }

    private byte[] header(long data) {
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        le32(h, 4, 36 + data);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        le32(h, 16, 16);           // size of the format block
        le16(h, 20, 1);            // plain PCM
        le16(h, 22, 1);            // mono
        le32(h, 24, sampleRate);
        le32(h, 28, sampleRate * 2L); // bytes per second
        le16(h, 32, 2);            // bytes per sample
        le16(h, 34, 16);           // bits per sample
        put(h, 36, "data");
        le32(h, 40, data);
        return h;
    }

    private static void put(byte[] h, int at, String s) {
        for (int i = 0; i < s.length(); i++) h[at + i] = (byte) s.charAt(i);
    }

    private static void le32(byte[] h, int at, long v) {
        h[at] = (byte) v;
        h[at + 1] = (byte) (v >> 8);
        h[at + 2] = (byte) (v >> 16);
        h[at + 3] = (byte) (v >> 24);
    }

    private static void le16(byte[] h, int at, int v) {
        h[at] = (byte) v;
        h[at + 1] = (byte) (v >> 8);
    }
}
