package com.paltech.salahos;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.media.AudioManager;
import android.view.WindowManager;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
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
    private static final String TAG = "SalahHost";
    private static final int ADB_PORT = 5555;

    private final MainActivity act;
    private final SharedPreferences prefs;
    private final File priv;
    private final File pub;
    private volatile String last = "idle";
    volatile boolean trusted;
    /**
     * True while the page on screen is one of our own bundled files. Those ship inside the APK and
     * cannot be replaced from the network, so they are allowed the little that first-run setup
     * needs — Wi-Fi and the hotspot — and nothing else. Everything heavier stays behind
     * {@link #trusted}, which only the hosted site earns.
     */
    volatile boolean local;

    private HotspotServer server;
    private CaptivePortal portal;
    private DhcpServer dhcp;
    private volatile String netPhase = "";
    private volatile String netWhy = "";
    private volatile String apSsid = "";
    private volatile String apPass = "";
    private volatile String apUrl = "";
    private volatile String preScan = "";
    private volatile long preScanAt = 0;
    private final Object radio = new Object();   /* one thread at a time may touch the Wi-Fi radio */

    /**
     * What the frame could see before it became an access point.
     *
     * One radio: the moment it is broadcasting a network of its own it can no longer look for
     * anyone else's, so asking it to scan while the buyer is standing in the setup page returns
     * nothing at all. The list is taken a few seconds earlier, while the frame is still only
     * listening, and that is what the phone is shown.
     */
    String preScan() { return preScan; }

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

    private static String q(String v) { return quote(v); }

    static String quote(String v) { return "'" + v.replace("'", "'\\''") + "'"; }

    /** op: status, scan, connect or forget. sec: open, owe, wep, wpa2 or wpa3. Poll wifiBusy() and read wifiResult(). */
    @JavascriptInterface
    public void wifi(final String op, final String ssid, final String sec, final String pass) {
        if (!(trusted || local) || wifiBusy) return;
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
                /* Wi-Fi set by hand counts as set: the wizard has no reason to ask again either. */
                if ("connect".equals(op) && wifiOut != null && wifiOut.toLowerCase().contains("connected to"))
                    markNetDone();
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

    // ---- Updates: version.json and salah-os.apk are published to the apk branch by the build ----
    private static final String BASE = "https://raw.githubusercontent.com/nsharawi/salah-os-frame/apk/";
    private volatile String updMsg = "idle";
    private volatile boolean updBusy;
    private volatile int remoteCode;
    private volatile String remoteName = "";

    private long localCode() {
        try { return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).getLongVersionCode(); }
        catch (Throwable t) { return 0; }
    }

    private String localName() {
        try { return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; }
        catch (Throwable t) { return ""; }
    }

    private volatile boolean screenBusy;

    /** The display tells us when a prayer countdown or adhan is showing; updates wait until it is over. */
    @JavascriptInterface
    public void setBusy(boolean b) {
        if (trusted) screenBusy = b;
    }

    /** Called every 30 minutes. Installs a newer version as soon as there is one, except during a prayer alert. */
    void autoCheck() {
        update(!screenBusy, false);
    }

    private void update(final boolean install, final boolean manual) {
        if (updBusy) return;
        updBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try { doUpdate(install, manual); }
                catch (Throwable t) { updMsg = "error: " + t.getClass().getSimpleName() + " " + t.getMessage(); }
                finally { updBusy = false; }
            }
        }).start();
    }

    private InputStream open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        return c.getInputStream();
    }

    private void doUpdate(boolean install, boolean manual) throws Exception {
        updMsg = "checking";
        StringBuilder sb = new StringBuilder();
        try (InputStream in = open(BASE + "version.json?t=" + System.currentTimeMillis())) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
        }
        JSONObject o = new JSONObject(sb.toString());
        remoteCode = o.getInt("versionCode");
        remoteName = o.getString("versionName");
        String sha = o.getString("sha256");
        if (remoteCode <= localCode()) { updMsg = "current"; return; }
        if (!install) { updMsg = "available"; return; }
        int tries = prefs.getInt("upd_try_" + remoteCode, 0);
        if (!manual && tries >= 3) { updMsg = "failed"; return; }
        prefs.edit().putInt("upd_try_" + remoteCode, tries + 1).apply();
        updMsg = "installing";
        File f = new File(act.getFilesDir(), "update.apk");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = open(BASE + "salah-os.apk?t=" + System.currentTimeMillis());
             FileOutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) { out.write(b, 0, n); md.update(b, 0, n); }
        }
        StringBuilder hex = new StringBuilder();
        for (byte x : md.digest()) hex.append(String.format("%02x", x));
        if (!hex.toString().equalsIgnoreCase(sha)) { f.delete(); updMsg = "error: checksum"; return; }
        Dadb d = null;
        try {
            d = connect();
            d.push(f, "/data/local/tmp/salahos-update.apk", 420, System.currentTimeMillis());
            // The install replaces this app, so the restart is done by a command that outlives it.
            d.open("shell:nohup sh -c 'pm install -r /data/local/tmp/salahos-update.apk; rm -f /data/local/tmp/salahos-update.apk; am start -n com.paltech.salahos/.MainActivity' >/data/local/tmp/salahos-update.log 2>&1 &");
            Thread.sleep(2500);
        } finally {
            try { if (d != null) d.close(); } catch (Throwable ignored) { }
        }
    }

    @JavascriptInterface
    public String updateState() {
        return "{\"cur\":\"" + localName() + "\",\"code\":" + localCode() + ",\"remote\":\"" + remoteName
                + "\",\"rc\":" + remoteCode + ",\"s\":\"" + updMsg.replace("\"", "'") + "\",\"busy\":" + updBusy + "}";
    }

    /** install=false only checks. install=true installs now if a newer version exists. */
    @JavascriptInterface
    public void updateCheck(boolean install) {
        if (!trusted) return;
        update(install, install);
    }

    /** Runs a command over the frame's control channel from anywhere in the app (used by technician mode). */
    static String shell(Context c, String cmd) {
        Dadb d = null;
        try {
            d = Dadb.create("127.0.0.1", ADB_PORT,
                    AdbKeyPair.read(new File(c.getFilesDir(), "adbkey"), new File(c.getFilesDir(), "adbkey.pub")));
            return d.shell(cmd).getAllOutput();
        } catch (Throwable t) {
            return "error: " + t.getClass().getSimpleName() + " " + t.getMessage();
        } finally {
            try { if (d != null) d.close(); } catch (Throwable ignored) { }
        }
    }

    static void shellAsync(final Context c, final String cmd) {
        new Thread(new Runnable() { @Override public void run() { shell(c, cmd); } }).start();
    }

    private String readFile(String name) {
        try {
            File f = new File(act.getFilesDir(), name);
            if (!f.exists()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable t) { return ""; }
    }

    /** The frame's Salah OS serial (SOS-....), written at setup. Empty when the frame has none yet. */
    @JavascriptInterface
    public String serial() {
        String s = readFile("serial.txt");
        return s.matches("[A-Z0-9-]{6,24}") ? s : "";
    }

    @JavascriptInterface
    public String versionName() { return localName(); }

    /** The display tells the app which way it reads, so the hidden technician corner stays opposite the Settings gear. */
    @JavascriptInterface
    public void setRtl(boolean rtl) {
        if (!trusted) return;
        if (prefs.getBoolean("rtl", false) != rtl) prefs.edit().putBoolean("rtl", rtl).apply();
    }

    boolean rtl() { return prefs.getBoolean("rtl", false); }

    /** True when the last control-channel test at start-up worked. */
    boolean controlOk() { return readFile("hostcheck.txt").endsWith(" ok"); }

    /** Opens Android's Wi-Fi screen. Only from the bundled "no internet" page, so a frame can be connected on first run. */
    @JavascriptInterface
    public void openWifi() {
        if (!act.isOffline() && !local) return;
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    act.startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                } catch (Throwable t) {
                    /* Some frame builds do not answer that request from an app. Ask the system
                       itself, over the control channel, rather than leave the button dead. */
                    android.util.Log.w("SalahHost", "Wi-Fi settings did not open directly: " + t);
                    new Thread(new Runnable() {
                        @Override public void run() { runShell("am start -a android.settings.WIFI_SETTINGS"); }
                    }).start();
                }
            }
        });
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

    /**
     * The language, chosen on the very first screen the frame ever shows and then never asked for
     * again — not on the no-network screen, not on the phone, and not by the wizard once the site
     * loads, which is handed the answer in its address.
     */
    @JavascriptInterface
    public void setLang(String x) {
        if (!local && !trusted) return;
        if (!"en".equals(x) && !"ar".equals(x)) return;
        prefs.edit().putString("ui_lang", x).apply();
    }

    @JavascriptInterface
    public String getLang() { return lang(); }

    /**
     * Whether this frame has put itself on a network - the phone handed it one, or the wizard's
     * own Wi-Fi page did. The site is told, so that its wizard drops the Wi-Fi step rather than
     * asking a buyer to join a network they are demonstrably already on.
     */
    boolean netDone() { return prefs.getBoolean("net_done", false); }

    void markNetDone() { prefs.edit().putBoolean("net_done", true).apply(); }

    String lang() { return prefs.getString("ui_lang", ""); }

    // ---- first run: no network yet ---------------------------------------------------------

    /**
     * Starts the part of setup that happens before the frame has any internet at all.
     *
     * A frame that has never been on a network raises a hotspot of its own and serves a setup
     * page to the buyer's phone; a frame that knows a network and simply cannot reach it right
     * now does nothing here and keeps retrying. Everything runs on its own thread — the page
     * asks, then polls {@link #netInfo()} — because none of it is quick and the one thing that
     * must not stall is the screen.
     */
    @JavascriptInterface
    public void netBegin() {
        if (!local) return;
        if ("ready".equals(netPhase) || "working".equals(netPhase)) return;
        netPhase = "working";
        netWhy = "";
        new Thread(new Runnable() {
            @Override public void run() { bringUpHotspot(); }
        }, "salah-net").start();
    }

    /**
     * Gets the radio ready and takes the list of networks while the buyer is still choosing a
     * language.
     *
     * This used to happen after they pressed Get started, and it is the slowest part of the whole
     * thing: the frame has one radio, so it cannot look around while it is broadcasting, and the
     * look takes the better part of ten seconds. Done here it costs nothing - the buyer is reading
     * the screen - and the code appears almost at once when they do press it. Nothing is said on
     * screen about any of this.
     */
    @JavascriptInterface
    public void netWarm() {
        if (!local) return;
        if (netPhase.length() > 0) return;          /* setup is already past this point */
        new Thread(new Runnable() {
            @Override public void run() { try { lookAround(); } catch (Throwable ignored) { } }
        }, "salah-warm").start();
    }

    private boolean scanFresh() {
        return preScan.length() > 0 && SystemClock.elapsedRealtime() - preScanAt < 90000;
    }

    /**
     * Puts the radio back to listening and asks what is in range.
     *
     * Only works when the frame is not already broadcasting: restarting the app leaves the
     * previous run's access point up, and a scan taken then finds nothing, which is exactly what
     * the buyer saw. So the radio is put back to listening, given a moment, and then asked.
     */
    private void lookAround() {
        synchronized (radio) {
            if (scanFresh()) return;                /* another thread just did this */
            if (!controlReady()) return;
            String saved = outShell("cmd wifi list-networks");
            if (saved != null && saved.matches("(?s).*\\n\\s*\\d+\\s+\\S.*")) return;
            outShell("cmd wifi stop-softap >/dev/null 2>&1; "
                   + "cmd wifi set-wifi-enabled enabled 2>&1; "
                   /* Android hands back scan results only where location is on - a photo frame
                      has no reason to have it on, and with it off the list comes back empty with
                      no error to say why. Scanning-always-available is asked for at the same
                      time, since the radio is otherwise allowed to go quiet. */
                   + "cmd location set-location-enabled true 2>&1; "
                   + "settings put secure location_mode 3 2>&1; "
                   + "cmd wifi set-scan-always-available enabled 2>&1; "
                   + "sleep 3");
            String s = outShell("cmd wifi start-scan 2>&1; sleep 6; cmd wifi list-scan-results");
            int seen = HotspotServer.parseScan(s).size();
            Log.i(TAG, "networks in range before going up: " + seen);
            if (seen == 0) {
                String head = s == null ? "" : s.trim();
                Log.w(TAG, "the scan said: " + (head.length() > 400 ? head.substring(0, 400) : head));
            }
            preScan = s == null ? "" : s;
            preScanAt = SystemClock.elapsedRealtime();
        }
    }

    /** phase: working, retry (a network is already saved), ready, or error. */
    @JavascriptInterface
    public String netInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", netPhase.length() == 0 ? "idle" : netPhase);
            o.put("why", netWhy);
            o.put("ssid", apSsid);
            o.put("pass", apPass);
            o.put("url", apUrl);
            o.put("name", frameName());
            o.put("state", server != null ? server.state() : "idle");
            o.put("joining", server != null ? server.joining() : "");
        } catch (Throwable ignored) { }
        return o.toString();
    }

    /** Takes the hotspot and the setup server down. Safe to call when neither is up. */
    @JavascriptInterface
    public void netEnd() {
        new Thread(new Runnable() {
            @Override public void run() { tearDownHotspot(); }
        }, "salah-net-end").start();
    }

    private void bringUpHotspot() {
        try {
            if (!controlReady()) {
                netWhy = "this frame cannot control its own Wi-Fi";
                netPhase = "error";
                return;
            }
            /* A frame that already knows a network is having an outage, not a first run. */
            String saved = outShell("cmd wifi list-networks");
            if (saved != null && saved.matches("(?s).*\\n\\s*\\d+\\s+\\S.*")) {
                netPhase = "retry";
                return;
            }

            /* Which build is talking, so a log can never be mistaken for a newer one again. */
            String ver = "?";
            try { ver = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; }
            catch (Throwable ignored) { }
            Log.i(TAG, "Salah OS " + ver + " bringing up the hotspot");

            /* Usually already done, while the buyer was choosing a language - see netWarm(). */
            if (scanFresh()) Log.i(TAG, "using the list taken while the language was being chosen");
            else lookAround();

            String ssid = frameName();
            String pass = newPassword();

            /*
              No band argument. Asked for 2.4 GHz explicitly this ROM brings the access point up
              and then reports "Soft AP failed to start" in the same breath; left alone it picks a
              2.4 GHz channel itself and says only that it worked. Watched on the frame on
              12 November 2026. The printed word is not trusted either way — what counts is
              whether an access point interface appears.
            */
            String out = outShell("cmd wifi stop-softap >/dev/null 2>&1; sleep 1; "
                    + "cmd wifi start-softap " + quote(ssid) + " wpa2 " + quote(pass));
            String iface = apInterface(out);
            for (int i = 0; i < 16 && iface == null; i++) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                iface = apInterface(outShell("dumpsys wifi 2>/dev/null | grep -m 2 mApInstanceIdentifier"));
            }
            if (iface == null) {
                netWhy = "the frame would not raise its hotspot";
                netPhase = "error";
                Log.w(TAG, "start-softap said: " + out);
                return;
            }

            /*
              And now the part Android leaves undone. The shell command raises the radio only: it
              says itself that it does not start tethering, and the interface comes up with no
              address on it and nothing handing any out. So the frame gives itself the address at
              the top of the subnet and answers DHCP, DNS and HTTP on it by itself.
            */
            /*
              The address, and then a routing rule for the subnet it is on.
              Android routes an app's traffic through the network that app is bound to, and with
              the frame's own Wi-Fi down there is none - so answering the phone failed with
              "network is unreachable" even though the phone was sitting on the other end of a
              working access point. The rule points anything addressed to this subnet at the main
              table, where the interface's own route lives.
            */
            String addr = outShell("ip addr flush dev " + iface + " 2>&1; "
                   + "ip addr add " + DhcpServer.SELF + "/24 dev " + iface + " 2>&1; "
                   + "ip link set " + iface + " up 2>&1; "
                   + "ip rule del to " + DhcpServer.NET + ".0/24 2>/dev/null; "
                   + "ip rule add to " + DhcpServer.NET + ".0/24 lookup main pref 11000 2>&1; "
                   + "ip -o -4 addr show dev " + iface + " 2>&1; ip rule list 2>&1 | head -8");
            Log.i(TAG, "address on " + iface + ": " + (addr == null ? "" : addr.trim()));

            /*
              Ports 53, 67 and 80 are the kernel's by default. Opening them to ordinary processes
              lets the app answer on them directly, which matters most for DHCP: a phone checks
              that the answer came from port 67 and discards anything else, and an answer rewritten
              on the way out by the firewall is not reliably accepted. Where the kernel has no such
              setting the servers fall back and the firewall carries the ports instead.
            */
            Log.i(TAG, "low ports: " + outShell(
                    "sysctl -w net.ipv4.ip_unprivileged_port_start=53 2>&1; "
                  + "echo 53 > /proc/sys/net/ipv4/ip_unprivileged_port_start 2>&1; "
                  + "cat /proc/sys/net/ipv4/ip_unprivileged_port_start 2>&1").trim());

            if (dhcp == null) dhcp = new DhcpServer();
            dhcp.start();
            if (server == null) server = new HotspotServer(act, this);
            server.start();
            if (portal == null) portal = new CaptivePortal(this, dhcp, server);
            portal.start(DhcpServer.SELF, iface);

            apSsid = ssid;
            apPass = pass;
            apUrl = "http://" + DhcpServer.SELF + (server.port() == 80 ? "" : ":" + server.port()) + "/";
            netPhase = "ready";
            Log.i(TAG, "hotspot " + ssid + " up on " + iface);
        } catch (Throwable t) {
            netWhy = t.getClass().getSimpleName();
            netPhase = "error";
            Log.w(TAG, "hotspot: " + t);
        }
    }

    /** The access point's interface name, which the frame prints as mApInstanceIdentifier. */
    private static String apInterface(String out) {
        if (out == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("mApInstanceIdentifier=\\s*(\\w+)").matcher(out);
        return m.find() ? m.group(1) : null;
    }

    private void tearDownHotspot() {
        try { if (server != null) server.stop(); } catch (Throwable ignored) { }
        try { if (portal != null) portal.stop(); } catch (Throwable ignored) { }
        try { if (dhcp != null) dhcp.stop(); } catch (Throwable ignored) { }
        apSsid = "";
        apPass = "";
        apUrl = "";
        if (!"ready".equals(netPhase)) { netPhase = ""; return; }
        netPhase = "";
        try { outShell("ip rule del to " + DhcpServer.NET + ".0/24 2>/dev/null; cmd wifi stop-softap"); }
        catch (Throwable ignored) { }
    }

    boolean hotspotUp() { return server != null && server.isRunning(); }

    /**
     * True from the moment first-run setup starts until it is finished with.
     *
     * The retry that reloads the site every fifteen seconds has to hold off for all of it, not
     * only for the part where the hotspot is already up: raising it takes the better part of
     * twenty seconds, and a reload in the middle of that threw the screen back to the beginning
     * and started it over, which is the flicker - picker, spinner, picker, spinner, code.
     */
    boolean netBusy() {
        return "working".equals(netPhase) || "ready".equals(netPhase);
    }

    private static boolean looksFailed(String out) {
        if (out == null) return true;
        String s = out.toLowerCase();
        return s.contains("error") || s.contains("fail") || s.contains("exception") || s.contains("usage:");
    }

    /** True when the frame answers over its own adb channel, which everything here needs. */
    private boolean controlReady() {
        return "ok".equals(runShell("echo ok"));
    }

    /**
     * This frame's name, which is also its hotspot: SalahOS- and six characters worked out from
     * the serial. The assembly tool computes the same name the same way, so the one on the carton
     * is the one the buyer sees on their phone.
     */
    String frameName() {
        String had = prefs.getString("frame_name", "");
        if (had.length() > 0) return had;
        String serial = outShell("getprop ro.serialno").trim();
        if (serial.length() == 0 || serial.contains("error")) {
            /* Falls back to a per-device id, which is stable but will not match the carton. */
            serial = android.provider.Settings.Secure.getString(act.getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);
        }
        String name = "SalahOS-" + crockford(serial == null ? "" : serial);
        prefs.edit().putString("frame_name", name).apply();
        return name;
    }

    /** First 30 bits of the hash, read as six characters of Crockford's alphabet. */
    private static String crockford(String serial) {
        final String A = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(serial.getBytes("US-ASCII"));
            long bits = ((long) (h[0] & 255) << 22) | ((long) (h[1] & 255) << 14)
                    | ((long) (h[2] & 255) << 6) | ((long) (h[3] & 255) >> 2);
            StringBuilder b = new StringBuilder();
            for (int i = 5; i >= 0; i--) b.append(A.charAt((int) ((bits >> (i * 5)) & 31)));
            return b.toString();
        } catch (Throwable t) {
            return "000000";
        }
    }

    /**
     * A fresh password every time the hotspot comes up. It is never typed — it travels inside the
     * QR code — so there is nothing to gain by making it memorable, and everything to lose by
     * deriving it from the name printed on the box.
     */
    private static String newPassword() {
        final String A = "abcdefghjkmnpqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 14; i++) b.append(A.charAt(r.nextInt(A.length())));
        return b.toString();
    }
}
