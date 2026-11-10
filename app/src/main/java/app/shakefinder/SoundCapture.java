package app.shakefinder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;

/**
 * Records the microphone on its own thread and feeds the samples to the sound
 * analysis. Nothing is saved or sent anywhere; the audio only lives in memory
 * for the second and a half being analysed.
 */
final class SoundCapture {

    private volatile Thread active; // the recording thread in charge, or null
    private volatile SoundRpm dsp;
    private volatile String error;
    private volatile File wavFile;       // where to save the sound, or null when not saving
    private volatile double wavSeconds;  // how much has been saved so far

    /** Longest sound file: five minutes, about 26 MB. */
    static final int MAX_WAV_SECONDS = 300;

    /** Saves the microphone sound to this file from now on. Null stops saving and finishes the file. */
    void recordTo(File f) {
        wavSeconds = 0;
        wavFile = f;
    }

    double wavSeconds() {
        return wavSeconds;
    }

    /** The analysis being fed, or null until recording has started. */
    SoundRpm dsp() {
        return dsp;
    }

    boolean isRunning() {
        return active != null;
    }

    /** A reason recording could not start, or null. */
    String error() {
        return error;
    }

    /** The caller must already hold the RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission")
    void start(Context ctx) {
        if (active != null) return;
        error = null;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            AudioRecord rec = null;
            WavWriter writer = null;
            File writerFile = null;
            int rate = 44100;
            try {
                // "Unprocessed" skips the phone's noise suppression and automatic
                // gain, which would smear the engine's low notes.
                int source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                if (am != null && "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))) {
                    source = MediaRecorder.AudioSource.UNPROCESSED;
                }
                for (int sr : new int[]{44100, 48000}) {
                    int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (min <= 0) continue;
                    AudioRecord r = new AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, sr)); // about half a second
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        rec = r;
                        rate = sr;
                        dsp = new SoundRpm(sr);
                        break;
                    }
                    r.release();
                }
                if (rec == null) {
                    error = "The microphone could not be opened.";
                    return;
                }
                rec.startRecording();
                short[] buf = new short[2048];
                while (active == Thread.currentThread()) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n < 0) {
                        error = "The microphone stopped.";
                        break;
                    }
                    if (n == 0) continue;
                    dsp.feed(buf, n);
                    // Saving the sound, when a recording is running.
                    File want = wavFile;
                    if (writer != null && (want == null || !want.equals(writerFile))) {
                        try { writer.close(); } catch (IOException ignored) { }
                        writer = null;
                    }
                    if (want != null && writer == null) {
                        try {
                            writer = new WavWriter(want, rate, MAX_WAV_SECONDS);
                            writerFile = want;
                        } catch (IOException e) {
                            wavFile = null; // can't write there: carry on without the sound file
                        }
                    }
                    if (writer != null) {
                        try {
                            writer.write(buf, n);
                            wavSeconds = writer.seconds();
                        } catch (IOException e) {
                            try { writer.close(); } catch (IOException ignored) { }
                            writer = null;
                            wavFile = null;
                        }
                    }
                }
            } catch (Throwable failure) {
                error = "The microphone could not be used.";
            } finally {
                if (writer != null) {
                    try { writer.close(); } catch (IOException ignored) { }
                }
                if (rec != null) {
                    try { rec.stop(); } catch (IllegalStateException ignored) { }
                    rec.release();
                }
                if (active == Thread.currentThread()) active = null;
            }
        }, "mic");
        active = t;
        t.start();
    }

    void stop() {
        active = null;
    }
}
