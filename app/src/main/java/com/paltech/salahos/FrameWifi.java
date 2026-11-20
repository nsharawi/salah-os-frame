package com.paltech.salahos;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * The frame's Wi-Fi, through Android itself.
 *
 * The first way this was done ran `cmd wifi` over a connection to the frame's own debugging
 * service. That needed the debugging service listening on the network and a key the frame had
 * been told to trust, both of which live where a factory reset wipes them, and neither of which
 * belongs on a frame in someone's home. As the device owner the app is allowed to do the same
 * things by asking Android directly, so this is that: nothing to switch on, nothing to trust,
 * and nothing left open.
 *
 * What it gives back is worded the way `cmd wifi` words it, because the screens that read it
 * were written against that and have no reason to change.
 */
final class FrameWifi {
    private static final String TAG = "SalahWifi";

    private FrameWifi() {}

    /** Whether the app is this frame's device owner. Everything here depends on it. */
    static boolean isOwner(Context ctx) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isDeviceOwnerApp(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Android will not show an app the networks in range unless it may know where the frame is,
     * since the two amount to the same thing. The owner can give itself that, and switch location
     * on, without anyone being asked - there is nobody at a frame to ask.
     */
    private static void ready(Context ctx) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName me = new ComponentName(ctx, Admin.class);
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                dpm.setPermissionGrantState(me, ctx.getPackageName(), Manifest.permission.ACCESS_FINE_LOCATION,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                for (int i = 0; i < 20 && ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED; i++) SystemClock.sleep(100);
            }
            if (Build.VERSION.SDK_INT >= 30) dpm.setLocationEnabled(me, true);
        } catch (Throwable t) {
            Log.w(TAG, "could not get ready: " + t);
        }
    }

    private static WifiManager wm(Context ctx) {
        return (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private static String bare(String ssid) {
        if (ssid == null) return "";
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) return ssid.substring(1, ssid.length() - 1);
        return ssid;
    }

    /** The network the frame is on, or nothing. */
    private static String current(WifiManager w) {
        try {
            WifiInfo i = w.getConnectionInfo();
            if (i == null || i.getSupplicantState() != SupplicantState.COMPLETED || i.getNetworkId() == -1) return "";
            String s = bare(i.getSSID());
            return "<unknown ssid>".equals(s) ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    static String status(Context ctx) {
        ready(ctx);
        WifiManager w = wm(ctx);
        if (w == null) return "Wifi is not available";
        if (!w.isWifiEnabled()) return "Wifi is disabled";
        String s = current(w);
        return "Wifi is enabled\n" + (s.length() > 0 ? "Wifi is connected to \"" + s + "\"" : "Wifi is not connected");
    }

    static String scan(Context ctx) {
        ready(ctx);
        WifiManager w = wm(ctx);
        if (w == null) return "";
        try {
            if (!w.isWifiEnabled()) { w.setWifiEnabled(true); SystemClock.sleep(2500); }
            w.startScan();
            SystemClock.sleep(3500);
            List<ScanResult> found = w.getScanResults();
            StringBuilder b = new StringBuilder("    BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags\n");
            long now = SystemClock.elapsedRealtime();
            if (found != null) for (ScanResult r : found) {
                double age = Math.max(0, (now - r.timestamp / 1000) / 1000.0);
                b.append("  ").append(r.BSSID).append("       ").append(r.frequency).append("        ")
                 .append(r.level).append("              ").append(String.format(java.util.Locale.US, "%.3f", age))
                 .append("    ").append(r.SSID == null ? "" : r.SSID).append("    ")
                 .append(r.capabilities == null ? "" : r.capabilities).append('\n');
            }
            Log.i(TAG, "scan: " + (found == null ? 0 : found.size()) + " networks");
            return b.toString();
        } catch (Throwable t) {
            Log.w(TAG, "scan failed: " + t);
            return "error: " + t;
        }
    }

    @SuppressWarnings("deprecation")
    static String connect(Context ctx, String ssid, String sec, String pass) {
        ready(ctx);
        WifiManager w = wm(ctx);
        if (w == null) return "Wifi is not available";
        int id = -1;
        try {
            if (!w.isWifiEnabled()) { w.setWifiEnabled(true); SystemClock.sleep(2500); }
            forgetQuietly(w, ssid);          /* an old entry with an old password must not win */

            WifiConfiguration c = new WifiConfiguration();
            c.SSID = "\"" + ssid + "\"";
            boolean keyed = pass != null && pass.length() > 0;
            if ("wpa2".equals(sec)) {
                if (Build.VERSION.SDK_INT >= 31) c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
                else c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                c.preSharedKey = "\"" + pass + "\"";
            } else if ("wpa3".equals(sec)) {
                if (Build.VERSION.SDK_INT >= 31) c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
                else { c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE); }
                c.preSharedKey = "\"" + pass + "\"";
            } else if ("wep".equals(sec)) {
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
                if (keyed) { c.wepKeys[0] = pass.matches("[0-9A-Fa-f]{10}|[0-9A-Fa-f]{26}") ? pass : "\"" + pass + "\""; c.wepTxKeyIndex = 0; }
            } else if ("owe".equals(sec)) {
                if (Build.VERSION.SDK_INT >= 31) c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE);
                else { c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE); }
            } else {
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            }

            id = w.addNetwork(c);
            if (id == -1) { Log.w(TAG, "the network was refused"); return "error: network refused\nWifi is not connected"; }
            w.enableNetwork(id, true);

            /* Joined means on it with an address, not merely trying. A wrong password shows as
               never getting there, so that is what is waited for. */
            for (int i = 0; i < 30; i++) {
                SystemClock.sleep(500);
                if (ssid.equals(current(w))) {
                    Log.i(TAG, "joined " + ssid);
                    return "Wifi is enabled\nWifi is connected to \"" + ssid + "\"";
                }
            }
            /* It did not take. Leaving it saved would have the frame retry a wrong password for
               ever and show the network as known when it is not. */
            w.removeNetwork(id);
            Log.i(TAG, "could not join " + ssid);
            return "Wifi is enabled\nWifi is not connected";
        } catch (Throwable t) {
            Log.w(TAG, "connect failed: " + t);
            try { if (id != -1) w.removeNetwork(id); } catch (Throwable ignored) { }
            return "error: " + t + "\nWifi is not connected";
        }
    }

    static String forget(Context ctx, String ssid) {
        ready(ctx);
        WifiManager w = wm(ctx);
        if (w == null) return "Wifi is not available";
        forgetQuietly(w, ssid);
        SystemClock.sleep(1500);
        return status(ctx);
    }

    /** Every saved network, forgotten: what starting the frame's setup over means for Wi-Fi. */
    static void forgetAll(Context ctx) {
        ready(ctx);
        forgetQuietly(wm(ctx), null);
    }

    @SuppressWarnings("deprecation")
    private static void forgetQuietly(WifiManager w, String ssid) {
        try {
            List<WifiConfiguration> saved = w.getConfiguredNetworks();
            if (saved == null) return;
            for (WifiConfiguration c : saved)
                if (ssid == null || ssid.equals(bare(c.SSID))) w.removeNetwork(c.networkId);
        } catch (Throwable t) {
            Log.w(TAG, "forget failed: " + t);
        }
    }
}
