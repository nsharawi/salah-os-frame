package com.paltech.salahos;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Technician mode's own Back / Home / Recents bar. This frame's Android has no navigation bar,
 * so while a technician is in another app this small bar floats at the bottom of the screen.
 */
public class NavService extends Service {
    private WindowManager wm;
    private View bar;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable idle = new Runnable() {
        @Override public void run() {
            if (Tech.expired() || !Tech.unlocked) { lock(); return; }
            ui.postDelayed(this, 30000);
        }
    };

    static boolean allowed(Context c) {
        try { return Settings.canDrawOverlays(c); } catch (Throwable t) { return false; }
    }

    static void start(Context c) {
        try { c.startService(new Intent(c, NavService.class)); } catch (Throwable ignored) { }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, NavService.class)); } catch (Throwable ignored) { }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        if (bar == null) show();
        ui.removeCallbacks(idle);
        ui.postDelayed(idle, 30000);
        return START_NOT_STICKY;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView button(String glyph, String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setContentDescription(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(72));
        t.setMinHeight(dp(48));
        t.setOnClickListener(l);
        return t;
    }

    private void show() {
        if (!allowed(this)) {
            // Try to give ourselves the permission through the frame's control channel, then retry once.
            new Thread(new Runnable() {
                @Override public void run() {
                    HostControl.shell(NavService.this, "appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                    ui.postDelayed(new Runnable() { @Override public void run() { if (bar == null && allowed(NavService.this)) show(); } }, 800);
                }
            }).start();
            return;
        }
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setPadding(dp(8), 0, dp(8), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE6171D1A);
            bg.setCornerRadius(dp(28));
            bg.setStroke(dp(1), 0x40FFFFFF);
            row.setBackground(bg);
            row.addView(button("◀", "Back", new View.OnClickListener() {
                @Override public void onClick(View v) { key(4); }
            }));
            row.addView(button("●", "Home", new View.OnClickListener() {
                @Override public void onClick(View v) { home(); }
            }));
            row.addView(button("■", "Recent apps", new View.OnClickListener() {
                @Override public void onClick(View v) { key(187); }
            }));
            TextView lockBtn = button("Lock", "Lock and return to Salah OS", new View.OnClickListener() {
                @Override public void onClick(View v) { lock(); }
            });
            lockBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            row.addView(lockBtn);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, dp(56),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(12);
            wm.addView(row, lp);
            bar = row;
        } catch (Throwable t) { bar = null; }
    }

    /** Back and Recents are sent through the frame's control channel (they need system rights). */
    private void key(final int code) {
        Tech.touch();
        new Thread(new Runnable() {
            @Override public void run() { HostControl.shell(NavService.this, "input keyevent " + code); }
        }).start();
    }

    private void home() {
        Tech.touch();
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) { }
        // If Android did not bring Salah OS forward, press Home through the control channel instead.
        ui.postDelayed(new Runnable() {
            @Override public void run() { if (!MainActivity.resumed) HostControl.shellAsync(getApplicationContext(), "input keyevent 3"); }
        }, 700);
    }

    private void lock() {
        Tech.unlocked = false;
        home();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try { if (bar != null && wm != null) wm.removeView(bar); } catch (Throwable ignored) { }
        bar = null;
        super.onDestroy();
    }
}
