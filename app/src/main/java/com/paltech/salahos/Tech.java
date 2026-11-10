package com.paltech.salahos;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Technician mode: a PIN-protected way to use the frame as a normal tablet.
 * Exposed as window.Tech to the bundled tech.html page only (never to the website).
 * The PIN is stored on the frame as a salted hash in files/tech.pin, written by the setup script.
 */
public class Tech {
    static final long IDLE_MS = 10L * 60 * 1000;
    private static final int MAX_FAILS = 5;
    private static final long LOCKOUT_MS = 5L * 60 * 1000;

    static volatile boolean unlocked;
    static volatile long lastUse;
    private static int fails;
    private static long lockedUntil;

    private final MainActivity act;

    Tech(MainActivity a) { act = a; }

    static void touch() { lastUse = System.currentTimeMillis(); }
    static boolean expired() { return unlocked && System.currentTimeMillis() - lastUse > IDLE_MS; }

    private String pinRecord() {
        try {
            File f = new File(act.getFilesDir(), "tech.pin");
            if (!f.exists()) return "";
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) { return ""; }
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    /** Record format: pbkdf2$<iterations>$<salt hex>$<hash hex> (SHA-256, 32 bytes). */
    private static boolean matches(String record, String pin) {
        try {
            String[] p = record.split("\\$");
            if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
            byte[] want = unhex(p[3]);
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), unhex(p[2]), Integer.parseInt(p[1]), want.length * 8);
            byte[] got = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(want, got);
        } catch (Throwable t) { return false; }
    }

    /** "none" when no PIN has been set on this frame, "locked:<seconds>" after too many wrong tries, else "set". */
    @JavascriptInterface
    public String pinState() {
        long wait = lockedUntil - System.currentTimeMillis();
        if (wait > 0) return "locked:" + (wait / 1000 + 1);
        return pinRecord().isEmpty() ? "none" : "set";
    }

    /** Returns "ok", "bad:<tries left>" or "locked:<seconds>". With no PIN set, any entry opens (unprovisioned frame). */
    @JavascriptInterface
    public synchronized String tryPin(String pin) {
        long now = System.currentTimeMillis();
        if (lockedUntil > now) return "locked:" + ((lockedUntil - now) / 1000 + 1);
        String rec = pinRecord();
        if (rec.isEmpty() || (pin != null && matches(rec, pin))) {
            fails = 0; unlocked = true; touch();
            return "ok";
        }
        if (++fails >= MAX_FAILS) { fails = 0; lockedUntil = now + LOCKOUT_MS; return "locked:" + (LOCKOUT_MS / 1000); }
        return "bad:" + (MAX_FAILS - fails);
    }

    @JavascriptInterface
    public boolean isUnlocked() { return unlocked && !expired(); }

    private static String icon(Drawable d) {
        try {
            int s = 96;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, s, s);
            d.draw(c);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) { return ""; }
    }

    /** The apps a technician can open, as JSON: [{label, pkg, cls, icon}]. */
    @JavascriptInterface
    public String apps() {
        JSONArray out = new JSONArray();
        if (!isUnlocked()) return out.toString();
        try {
            PackageManager pm = act.getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            for (ResolveInfo r : list) {
                if (r.activityInfo == null || act.getPackageName().equals(r.activityInfo.packageName)) continue;
                JSONObject o = new JSONObject();
                o.put("label", String.valueOf(r.loadLabel(pm)));
                o.put("pkg", r.activityInfo.packageName);
                o.put("cls", r.activityInfo.name);
                o.put("icon", icon(r.loadIcon(pm)));
                out.put(o);
            }
        } catch (Throwable ignored) { }
        return out.toString();
    }

    @JavascriptInterface
    public void launch(final String pkg, final String cls) {
        if (!isUnlocked()) return;
        touch();
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    NavService.start(act);
                    act.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(new ComponentName(pkg, cls))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                } catch (Throwable ignored) { }
            }
        });
    }

    /** Frame details for the technician screen, as JSON. */
    @JavascriptInterface
    public String info() {
        JSONObject o = new JSONObject();
        try {
            o.put("serial", act.host().serial());
            o.put("version", act.host().versionName());
            o.put("control", act.host().controlOk());
            o.put("overlay", NavService.allowed(act));
            o.put("idleMinutes", IDLE_MS / 60000);
        } catch (Throwable ignored) { }
        return o.toString();
    }

    @JavascriptInterface
    public void restartApp() {
        if (!isUnlocked()) return;
        act.runOnUiThread(new Runnable() { @Override public void run() { lockNow(); act.restartApp(); } });
    }

    /** Lock and go back to the prayer display. */
    @JavascriptInterface
    public void lock() {
        act.runOnUiThread(new Runnable() { @Override public void run() { lockNow(); } });
    }

    /** Close the PIN pad without unlocking. */
    @JavascriptInterface
    public void close() { lock(); }

    void lockNow() {
        unlocked = false;
        NavService.stop(act);
        act.hideTech();
    }
}
