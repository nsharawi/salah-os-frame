package com.paltech.salahos;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
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

    // Unlocked state. Kept in memory and mirrored to preferences, so the navigation bar can come back if
    // Android restarts the app while a technician is in another app. A frame restart always locks.
    private static volatile long last;          // elapsedRealtime of the last technician action, 0 = locked
    private static long saved;
    private static int fails;
    private static long lockedUntil;

    private final MainActivity act;

    Tech(MainActivity a) { act = a; }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("frame", Context.MODE_PRIVATE); }

    private static int boot(Context c) {
        try { return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT); } catch (Throwable t) { return 0; }
    }

    static boolean isOpen(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (last == 0) {
            SharedPreferences p = prefs(c);
            long l = p.getLong("tech_last", 0);
            if (l > 0 && p.getInt("tech_boot", -1) == boot(c) && now >= l) last = l;
        }
        if (last == 0) return false;
        if (now - last > IDLE_MS) { setLocked(c); return false; }
        return true;
    }

    static void touch(Context c) {
        if (last == 0) return;
        last = SystemClock.elapsedRealtime();
        if (last - saved > 5000) save(c);
    }

    private static void save(Context c) {
        saved = last;
        prefs(c).edit().putLong("tech_last", last).putInt("tech_boot", boot(c)).apply();
    }

    static void setUnlocked(Context c) { last = SystemClock.elapsedRealtime(); save(c); }

    static void setLocked(Context c) { last = 0; saved = 0; prefs(c).edit().remove("tech_last").apply(); }

    private String pinRecord() {
        try {
            File f = new File(act.getFilesDir(), "tech.pin");
            // A frame built from the production image carries its PIN record in the image, so the PIN
            // survives a factory reset instead of falling back to "any six digits".
            if (!f.exists()) f = new File("/product/etc/salahos/tech.pin");
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

    /** A one-time unlock code from the console (HOTP, HMAC-SHA1, 6 digits) for this frame's secret,
        any of the next 20 counters; each works once. The secret comes from the frame's page (HostControl.otpSync). */
    private boolean codeOk(String pin) {
        if (pin == null || !pin.matches("[0-9]{6}")) return false;
        SharedPreferences p = prefs(act);
        String s = p.getString("otp_s", "");
        if (s.isEmpty()) return false;
        int n = p.getInt("otp_n", 0);
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(unhex(s), "HmacSHA1"));
            for (int c = n + 1; c <= n + 20; c++) {
                byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(c).array());
                int o = h[19] & 15;
                int v = ((h[o] & 127) << 24 | (h[o + 1] & 255) << 16 | (h[o + 2] & 255) << 8 | (h[o + 3] & 255)) % 1000000;
                if (String.format(java.util.Locale.US, "%06d", v).equals(pin)) { p.edit().putInt("otp_n", c).apply(); return true; }
            }
        } catch (Throwable t) { }
        return false;
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
        if (rec.isEmpty() || (pin != null && (matches(rec, pin) || codeOk(pin)))) {
            fails = 0; setUnlocked(act);
            return "ok";
        }
        if (++fails >= MAX_FAILS) { fails = 0; lockedUntil = now + LOCKOUT_MS; return "locked:" + (LOCKOUT_MS / 1000); }
        return "bad:" + (MAX_FAILS - fails);
    }

    @JavascriptInterface
    public boolean isUnlocked() { return isOpen(act); }

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
        touch(act);
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    NavAccess.show(act);
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
            o.put("overlay", NavAccess.connected());
            o.put("idleMinutes", IDLE_MS / 60000);
            o.put("usbDebug", usbDebugOn());
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) act.getSystemService(Context.DEVICE_POLICY_SERVICE);
            o.put("owner", dpm != null && dpm.isDeviceOwnerApp(act.getPackageName()));
        } catch (Throwable ignored) { }
        return o.toString();
    }

    /** Whether USB debugging is on. */
    private boolean usbDebugOn() {
        try { return Settings.Global.getInt(act.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1; } catch (Throwable t) { return false; }
    }

    /**
     * Service mode: turns USB debugging on or off, so a returned frame can be reached by the flasher
     * without opening the case. Only an unlocked technician can ask, and only a frame that Salah OS owns
     * can do it (Android lets a device owner change this one setting). Returns "on", "off" or "no:<why>".
     */
    @JavascriptInterface
    public String setUsbDebug(boolean on) {
        if (!isUnlocked()) return "no:locked";
        touch(act);
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) act.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isDeviceOwnerApp(act.getPackageName())) return "no:not the device owner";
            dpm.setGlobalSetting(new ComponentName(act, Admin.class), Settings.Global.ADB_ENABLED, on ? "1" : "0");
            android.util.Log.i("SalahTech", "usb debugging set " + (on ? "on" : "off") + " from technician mode");
            return usbDebugOn() ? "on" : "off";
        } catch (Throwable t) { return "no:" + t.getClass().getSimpleName(); }
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
        setLocked(act);
        NavAccess.hide();
        act.hideTech();
    }
}
