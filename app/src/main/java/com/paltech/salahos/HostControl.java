package com.paltech.salahos;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileWriter;
import java.util.Date;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * Lets the Settings page control the frame itself. Exposed to the page as window.SalahFrame.
 * Volume and brightness are set directly. Restart and shut down go through the frame's own adb
 * service on 127.0.0.1:5555, using a key made on this device (see frame-setup-control.bat).
 */
public class HostControl {
    private static final int ADB_PORT = 5555;

    private final MainActivity act;
    private final SharedPreferences prefs;
    private final File priv;
    private final File pub;
    private volatile String last = "idle";
    volatile boolean trusted;

    HostControl(MainActivity a) {
        act = a;
        prefs = a.getSharedPreferences("frame", Context.MODE_PRIVATE);
        priv = new File(a.getFilesDir(), "adbkey");
        pub = new File(a.getFilesDir(), "adbkey.pub");
        try {
            if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub);
        } catch (Throwable ignored) { }
    }

    /** Re-apply the saved brightness and volume (called at start). */
    void applySaved() {
        int b = prefs.getInt("brightness", -1);
        if (b >= 0) applyBrightness(b);
        int v = prefs.getInt("volume", -1);
        if (v >= 0) applyVolume(v);
    }

    /** Writes the result of a connection test to files/hostcheck.txt (read by the setup script). */
    void selfTest() {
        new Thread(new Runnable() {
            @Override public void run() {
                String r = runShell("echo ok");
                try (FileWriter w = new FileWriter(new File(act.getFilesDir(), "hostcheck.txt"))) {
                    w.write(new Date() + " " + r + "\n");
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    private AudioManager audio() { return (AudioManager) act.getSystemService(Context.AUDIO_SERVICE); }

    private void applyBrightness(final int pct) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                WindowManager.LayoutParams lp = act.getWindow().getAttributes();
                lp.screenBrightness = Math.max(0.05f, Math.min(1f, pct / 100f));
                act.getWindow().setAttributes(lp);
            }
        });
    }

    private void applyVolume(int pct) {
        AudioManager am = audio();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), 0);
    }

    @JavascriptInterface
    public String state() {
        AudioManager am = audio();
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int vol = Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
        return "{\"volume\":" + vol + ",\"brightness\":" + prefs.getInt("brightness", 70) + "}";
    }

    @JavascriptInterface
    public void setBrightness(int pct) {
        if (!trusted) return;
        pct = Math.max(5, Math.min(100, pct));
        prefs.edit().putInt("brightness", pct).apply();
        applyBrightness(pct);
    }

    @JavascriptInterface
    public void setVolume(int pct) {
        if (!trusted) return;
        pct = Math.max(0, Math.min(100, pct));
        prefs.edit().putInt("volume", pct).apply();
        applyVolume(pct);
    }

    @JavascriptInterface
    public void restartApp() {
        if (!trusted) return;
        act.runOnUiThread(new Runnable() {
            @Override public void run() { act.restartApp(); }
        });
    }

    /** name is one of: test, reboot, shutdown. Poll hostResult() for the outcome. */
    @JavascriptInterface
    public void hostAction(final String name) {
        if (!trusted) return;
        last = "running";
        new Thread(new Runnable() {
            @Override public void run() {
                if ("test".equals(name)) last = runShell("echo ok");
                else if ("reboot".equals(name)) last = fireShell("reboot");
                else if ("shutdown".equals(name)) last = fireShell("reboot -p");
                else last = "error: unknown action";
            }
        }).start();
    }

    @JavascriptInterface
    public String hostResult() { return last; }

    private volatile boolean wifiBusy;
    private volatile String wifiOut = "";

    private static String q(String v) { return "'" + v.replace("'", "'\\''") + "'"; }

    /** op: status, scan, connect or forget. sec: open, owe, wep, wpa2 or wpa3. Poll wifiBusy() and read wifiResult(). */
    @JavascriptInterface
    public void wifi(final String op, final String ssid, final String sec, final String pass) {
        if (!trusted || wifiBusy) return;
        final String cmd;
        if ("status".equals(op)) cmd = "cmd wifi status";
        else if ("scan".equals(op)) cmd = "cmd wifi start-scan; sleep 3; cmd wifi list-scan-results";
        else if ("connect".equals(op) && ssid != null && ssid.length() > 0
                && sec != null && sec.matches("open|owe|wep|wpa2|wpa3"))
            cmd = "cmd wifi connect-network " + q(ssid) + " " + sec
                    + (pass != null && pass.length() > 0 && !sec.equals("open") && !sec.equals("owe") ? " " + q(pass) : "")
                    + "; sleep 7; cmd wifi status";
        else if ("forget".equals(op) && ssid != null && ssid.length() > 0)
            cmd = "cmd wifi list-networks | awk -v s=" + q(ssid)
                    + " 'NR>1{id=$1; $1=\"\"; $NF=\"\"; sub(/^ +/,\"\"); sub(/ +$/,\"\"); if($0==s) print id}'"
                    + " | while read i; do cmd wifi forget-network $i; done; sleep 2; cmd wifi status";
        else return;
        wifiBusy = true;
        wifiOut = "";
        new Thread(new Runnable() {
            @Override public void run() {
                wifiOut = outShell(cmd);
                wifiBusy = false;
            }
        }).start();
    }

    @JavascriptInterface
    public boolean wifiBusy() { return wifiBusy; }

    @JavascriptInterface
    public String wifiResult() { return wifiOut; }

    /** Runs a command and returns what it printed. */
    String outShell(String cmd) {
        Dadb d = null;
        try {
            d = connect();
            return d.shell(cmd).getAllOutput();
        } catch (Throwable t) {
            return "error: " + t.getClass().getSimpleName() + " " + t.getMessage();
        } finally {
            try { if (d != null) d.close(); } catch (Throwable ignored) { }
        }
    }

    private Dadb connect() {
        return Dadb.create("127.0.0.1", ADB_PORT, AdbKeyPair.read(priv, pub));
    }

    /** Runs a command and waits for it to finish. */
    String runShell(String cmd) {
        Dadb d = null;
        try {
            d = connect();
            AdbShellResponse r = d.shell(cmd);
            return r.getExitCode() == 0 ? "ok" : "exit " + r.getExitCode() + ": " + r.getAllOutput().trim();
        } catch (Throwable t) {
            return "error: " + t.getClass().getSimpleName() + " " + t.getMessage();
        } finally {
            try { if (d != null) d.close(); } catch (Throwable ignored) { }
        }
    }

    /** Sends a command that ends the connection (reboot, power off) and does not wait for a reply. */
    String fireShell(String cmd) {
        Dadb d = null;
        try {
            d = connect();
            d.open("shell:" + cmd);
            Thread.sleep(1500);
            return "ok";
        } catch (Throwable t) {
            return "error: " + t.getClass().getSimpleName() + " " + t.getMessage();
        } finally {
            try { if (d != null) d.close(); } catch (Throwable ignored) { }
        }
    }
}
