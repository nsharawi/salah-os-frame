package com.paltech.salahos;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Workshop hardware checks, started by the flasher over USB:
 *   am start -n com.paltech.salahos/.MainActivity --es selftest screen|touch|sound|stop --es nonce ID
 * The screen check fills the panel with black, white, red, green and blue, one touch each.
 * The touch check shows five targets on the frame, one at a time. The sound check plays a test sound.
 * Each result is written to files/selftest.json (and to the log as SalahSelfTest) with the nonce it was
 * started with, so the flasher knows the answer belongs to its own request.
 */
public final class SelfTest {
    private static final String TAG = "SalahSelfTest";
    private final MainActivity act;
    private String nonce = "";
    private volatile AudioTrack playing;

    SelfTest(MainActivity a) { act = a; }

    void begin(String n) { nonce = n == null ? "" : n; }

    @JavascriptInterface
    public void result(String test, String status, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("nonce", nonce); o.put("test", test); o.put("status", status); o.put("detail", detail == null ? "" : detail);
            o.put("at", System.currentTimeMillis());
            File f = new File(act.getFilesDir(), "selftest.json");
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            Log.i(TAG, o.toString());
        } catch (Throwable t) { Log.w(TAG, "could not record the result: " + t); }
    }

    /** Three rising tones, twice, at full media volume: enough to hear a dead, quiet or rattling speaker. */
    @JavascriptInterface
    public void playSound() {
        new Thread(() -> {
            AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
            int was = -1;
            try {
                stopSound();
                if (am != null) { was = am.getStreamVolume(AudioManager.STREAM_MUSIC); am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0); }
                final int rate = 44100;
                final int[] hz = {440, 660, 880, 440, 660, 880};
                final int per = rate / 2;                       // half a second each
                short[] pcm = new short[per * hz.length];
                for (int n = 0; n < hz.length; n++)
                    for (int i = 0; i < per; i++) {
                        double env = Math.min(1.0, Math.min(i, per - i) / 1200.0);   // soft edges, no click
                        pcm[n * per + i] = (short) (Math.sin(2 * Math.PI * hz[n] * i / rate) * env * 0.8 * Short.MAX_VALUE);
                    }
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
                t.write(pcm, 0, pcm.length);
                playing = t;
                t.play();
                Log.i(TAG, "playing the test sound");
                Thread.sleep(3200);
            } catch (Throwable e) { Log.w(TAG, "test sound failed: " + e);
            } finally {
                stopSound();
                try { if (am != null && was >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, was, 0); } catch (Throwable ignored) { }
            }
        }, "selftest-sound").start();
    }

    void stopSound() {
        AudioTrack t = playing; playing = null;
        if (t != null) try { t.stop(); t.release(); } catch (Throwable ignored) { }
    }

    @JavascriptInterface
    public void close() { act.runOnUiThread(act::hideSelfTest); }
}
