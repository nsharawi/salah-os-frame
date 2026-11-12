package com.paltech.salahos;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Makes the frame match what a Salah OS frame should be, and keeps it that way.
 *
 * Everything here used to be done over USB from a Windows tool, which meant it only ever reached
 * the frame that happened to be plugged in. The app already has a root shell of its own, so it can
 * do the same work itself — and that changes what it is worth. A frame in someone's living room
 * picks up a new boot logo on its next update, and a frame whose vendor pushes an over-the-air
 * update that puts ONN's logo back repairs itself the next time it starts.
 *
 * It runs every start rather than once, because the point is not to apply something once but to
 * hold the frame at a known state. Each step reads the frame first and does nothing when it is
 * already right, so the usual run costs two hashes and no writes.
 *
 * Safety, since this writes to a boot partition on hardware nobody is watching:
 *   - every payload must match a hash compiled into this app, not one taken from the network, so
 *     a compromised repository cannot have a frame write arbitrary bytes into its bootloader;
 *   - the partition is only written when it currently holds a state we recognise;
 *   - the result is read back off the frame and checked;
 *   - any failure is logged and abandoned. A frame with ONN's logo works perfectly.
 */
final class Provision {
    private static final String TAG = "SalahProvision";

    /** Payloads live on main, not the apk branch, which the build republishes from scratch. */
    private static final String BASE =
            "https://raw.githubusercontent.com/nsharawi/salah-os-frame/main/provision/";

    /* The boot logo sits inside the bootloader partition as a plain image; these are its own
       sectors and nothing else. Confirmed against the partition on 12 November 2026. */
    private static final String LOGO_PART  = "/dev/block/by-name/bootloader_a";
    private static final int    LOGO_SEEK  = 11809;
    private static final int    LOGO_COUNT = 6001;
    private static final String LOGO_FILE  = "salah-logo.pad.bin";
    private static final String LOGO_SHA   = "52d7c77705dd4f14ec6e2550436bfdd3793a7a8894bb3a6a4a5fe62c2911bef6";
    /** ONN's original, so a frame that still has it is recognised rather than left alone. */
    private static final String LOGO_STOCK = "82b21a31d1a1075af389c557fa09a6e4b351944acfae796ba4512e1ffb98ecc9";

    /* Android looks in /oem/media before /system/media, and /oem here is a plain FAT partition
       outside verity — which is why the animation can be replaced without unlocking anything. */
    private static final String ANIM_DEST = "/oem/media/bootanimation.zip";
    private static final String ANIM_FILE = "salah-bootanimation.zip";
    private static final String ANIM_SHA  = "38a1d99340d2519a3432366859774cd24e7715286d5bc37ad62282e9a813f821";

    /** Frameo also registers itself as a home screen, so a frame could boot into it. */
    private static final String FRAMEO = "net.frameo.frame";

    private final Context ctx;
    private final HostControl host;
    private String su;

    Provision(Context ctx, HostControl host) { this.ctx = ctx; this.host = host; }

    void run() {
        try {
            su = rootPrefix();
            if (su == null) { Log.w(TAG, "no root on this frame; nothing to do"); return; }
            bootLogo();
            bootAnimation();
            disableFrameo();
        } catch (Throwable t) {
            Log.w(TAG, "provisioning stopped: " + t);
        }
    }

    /* ---------- the steps ------------------------------------------------------------------ */

    private void bootLogo() {
        String now = sha(shell("dd if=" + LOGO_PART + " bs=512 skip=" + LOGO_SEEK
                + " count=" + LOGO_COUNT + " 2>/dev/null | sha256sum"));
        if (LOGO_SHA.equals(now)) { Log.i(TAG, "boot logo already ours"); return; }
        if (!LOGO_STOCK.equals(now)) {
            /* Something else is in there. It could be a different hardware revision or a vendor
               update that changed the partition's layout, and writing blind could stop the frame
               booting. Leave it alone and say so. */
            Log.w(TAG, "boot logo is in an unrecognised state (" + brief(now) + "); leaving it alone");
            return;
        }

        File f = fetch(LOGO_FILE, LOGO_SHA);
        if (f == null) return;
        try {
            String tmp = "/data/local/tmp/salahos-logo.bin";
            if (!host.pushFile(f, tmp)) { Log.w(TAG, "could not push the boot logo"); return; }
            shell("sync; dd if=" + tmp + " of=" + LOGO_PART + " bs=512 seek=" + LOGO_SEEK
                    + " count=" + LOGO_COUNT + " conv=notrunc 2>&1; sync; rm -f " + tmp);
            String after = sha(shell("dd if=" + LOGO_PART + " bs=512 skip=" + LOGO_SEEK
                    + " count=" + LOGO_COUNT + " 2>/dev/null | sha256sum"));
            if (LOGO_SHA.equals(after)) Log.i(TAG, "boot logo written and verified");
            else Log.e(TAG, "boot logo did not verify after writing (" + brief(after) + ")");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private void bootAnimation() {
        String now = sha(shell("sha256sum " + ANIM_DEST + " 2>/dev/null"));
        if (ANIM_SHA.equals(now)) { Log.i(TAG, "boot animation already ours"); return; }

        File f = fetch(ANIM_FILE, ANIM_SHA);
        if (f == null) return;
        try {
            String tmp = "/data/local/tmp/salahos-anim.zip";
            if (!host.pushFile(f, tmp)) { Log.w(TAG, "could not push the boot animation"); return; }
            shell("mount -o remount,rw /oem 2>/dev/null || mount -o remount,rw /dev/block/by-name/media_data /oem");
            String mounted = shell("grep ' /oem ' /proc/mounts");
            if (!mounted.matches("(?s).*[ ,]rw[, ].*")) {
                Log.w(TAG, "/oem would not mount writable; leaving the animation alone");
                shell("rm -f " + tmp);
                return;
            }
            shell("mkdir -p /oem/media; cp " + tmp + " " + ANIM_DEST + "; sync; rm -f " + tmp);
            String after = sha(shell("sha256sum " + ANIM_DEST + " 2>/dev/null"));
            shell("mount -o remount,ro /oem 2>/dev/null");
            if (ANIM_SHA.equals(after)) Log.i(TAG, "boot animation installed and verified");
            else Log.e(TAG, "boot animation did not verify (" + brief(after) + ")");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private void disableFrameo() {
        String installed = shell("pm list packages " + FRAMEO);
        if (!installed.contains(FRAMEO)) return;
        String disabled = shell("pm list packages -d " + FRAMEO);
        if (disabled.contains(FRAMEO)) return;
        shell("pm disable-user --user 0 " + FRAMEO + " 2>&1");
        String check = shell("pm list packages -d " + FRAMEO);
        Log.i(TAG, check.contains(FRAMEO) ? "Frameo disabled" : "Frameo could not be disabled");
    }

    /* ---------- the plumbing --------------------------------------------------------------- */

    /** The stock su here takes "su 0 CMD" rather than "su -c". Find whichever answers. */
    private String rootPrefix() {
        for (String p : new String[]{"su 0", "su root"}) {
            String id = host.outShell(p + " id");
            if (id != null && id.contains("uid=0")) return p;
        }
        return null;
    }

    private String shell(String cmd) {
        String out = host.outShell(su + " sh -c " + quote(cmd));
        return out == null ? "" : out;
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /** sha256sum prints "<hash>  <name>"; take the hash. */
    private static String sha(String out) {
        if (out == null) return "";
        String t = out.trim();
        int sp = t.indexOf(' ');
        if (sp > 0) t = t.substring(0, sp);
        return t.matches("[0-9a-f]{64}") ? t : "";
    }

    private static String brief(String h) { return h == null || h.isEmpty() ? "unreadable" : h.substring(0, 12); }

    /**
     * Download a payload and refuse it unless it matches the hash compiled into this app. The
     * manifest is not consulted and no hash comes off the network: that is the whole point.
     */
    private File fetch(String name, String wantSha) {
        File out = new File(ctx.getCacheDir(), name);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + name + "?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) { Log.w(TAG, name + ": HTTP " + c.getResponseCode()); return null; }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) > 0) { md.update(b, 0, n); fo.write(b, 0, n); }
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            if (!wantSha.equals(sb.toString())) {
                Log.e(TAG, name + " does not match the expected hash; refusing it");
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                return null;
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "could not fetch " + name + ": " + t);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
