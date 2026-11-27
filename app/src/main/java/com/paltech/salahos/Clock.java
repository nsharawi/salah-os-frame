package com.paltech.salahos;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * The frame has no clock battery: after a power cut it starts with a wrong date until the network
 * sets it. Prayer times shown in that gap would be wrong. So the app keeps the last good time
 * (once a minute) and, when the frame starts with a clock earlier than that, puts the clock back to
 * the last good time. The network time then corrects it the moment the frame is online.
 * A plain restart keeps the clock, so this only acts after a power cut.
 */
final class Clock {
    /* Nothing before this can be a real time on a frame that runs this app (1 Nov 2026). */
    private static final long FLOOR = 1793491200000L;
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("frame", Context.MODE_PRIVATE); }

    /** Once a minute: remember the time, if it looks real. */
    static void tick(Context c) {
        long now = System.currentTimeMillis();
        if (now < FLOOR) return;
        SharedPreferences p = prefs(c);
        if (now > p.getLong("clock_good", 0)) p.edit().putLong("clock_good", now).putLong("clock_up", SystemClock.elapsedRealtime()).apply();
    }

    /** At start: a clock earlier than the last good time is put forward to it. Returns what it did. */
    static String restore(Context c) {
        long now = System.currentTimeMillis(), good = prefs(c).getLong("clock_good", 0);
        if (good == 0 || now >= good) return "ok";
        /* the frame has been running for a while since starting; count that too */
        long target = good + Math.min(SystemClock.elapsedRealtime(), 10L * 60 * 1000);
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(c, Admin.class);
            if (dpm == null || !dpm.isDeviceOwnerApp(c.getPackageName())) return "not-owner";
            /* the owner may set the time only while network time is off; switch it back on straight after */
            boolean auto = dpm.getAutoTimeEnabled(admin);
            if (auto) dpm.setAutoTimeEnabled(admin, false);
            boolean ok = dpm.setTime(admin, target);
            if (auto) dpm.setAutoTimeEnabled(admin, true);
            prefs(c).edit().putLong("clock_fixed", target).apply();
            return ok ? "restored" : "refused";
        } catch (Throwable t) {
            return "error: " + t.getClass().getSimpleName();
        }
    }
}
