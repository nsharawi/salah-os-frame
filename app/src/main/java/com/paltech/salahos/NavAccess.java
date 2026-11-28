package com.paltech.salahos;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Technician mode's own Back / Home / Recents / Lock bar. This frame's Android has no navigation bar,
 * so while a technician is in another app this bar floats at the bottom of the screen.
 * It is an accessibility service because Android keeps those running and lets them press Back and
 * Recents without any other permission. It never reads the screen, and shows nothing while locked.
 */
public class NavAccess extends AccessibilityService {
    static final String COMPONENT = "com.paltech.salahos/com.paltech.salahos.NavAccess";
    private static volatile NavAccess instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View bar;
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!Tech.isOpen(NavAccess.this)) { lock(); return; }
            ui.postDelayed(this, 20000);
        }
    };

    static boolean connected() { return instance != null; }

    /**
     * Makes sure Android has this service switched on and connected. Called at every app start and whenever the
     * bar is needed but missing. Some Android builds drop or fail to reconnect the service after a restart, so
     * when it is not connected the setting is cleared and set again, which makes Android connect it afresh.
     */
    static void ensure(final Context c) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                if (instance != null) return;
                String on = HostControl.shell(app, "settings get secure enabled_accessibility_services");
                if (on != null && on.contains(COMPONENT)) {
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) { }   // listed: give Android a moment to connect it
                    if (instance != null) return;
                    HostControl.shell(app, "settings put secure enabled_accessibility_services ''");
                    try { Thread.sleep(700); } catch (InterruptedException ignored) { }
                }
                HostControl.shell(app, "settings put secure enabled_accessibility_services " + COMPONENT
                        + "; settings put secure accessibility_enabled 1");
            }
        }).start();
    }

    /** Show the bar (called when a technician opens another app). Reconnects the service first if needed. */
    static void show(final Context c) {
        NavAccess n = instance;
        if (n != null) { n.ui.post(new Runnable() { @Override public void run() { NavAccess s = instance; if (s != null) s.showBar(); } }); return; }
        ensure(c);      // onServiceConnected shows the bar once Android connects the service
    }

    static void hide() {
        final NavAccess n = instance;
        if (n != null) n.ui.post(new Runnable() { @Override public void run() { n.hideBar(); } });
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        // Connected while a technician is already in another app (first use, or Android restarted us): show the bar.
        if (Tech.isOpen(this) && !MainActivity.resumed) showBar();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private ImageView button(int icon, String label, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setContentDescription(label);
        b.setScaleType(ImageView.ScaleType.CENTER);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) b.setBackgroundResource(tv.resourceId);
        b.setClickable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(88), dp(56));
        lp.leftMargin = lp.rightMargin = dp(2);
        b.setLayoutParams(lp);
        b.setColorFilter(0xFFDEE3E6);                 // on surface, dark scheme
        return b;
    }

    private void showBar() {
        ui.removeCallbacks(watch);
        ui.postDelayed(watch, 20000);
        if (bar != null) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setPadding(dp(12), 0, dp(12), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF303638);                      // M3 floating toolbar: surface container highest, dark teal scheme
            bg.setCornerRadius(dp(36));
            row.setBackground(bg);
            row.setElevation(dp(6));
            row.addView(button(R.drawable.ic_nav_back, "Back", new View.OnClickListener() {
                @Override public void onClick(View v) { Tech.touch(NavAccess.this); performGlobalAction(GLOBAL_ACTION_BACK); }
            }));
            row.addView(button(R.drawable.ic_nav_home, "Home", new View.OnClickListener() {
                @Override public void onClick(View v) { Tech.touch(NavAccess.this); home(); }
            }));
            row.addView(button(R.drawable.ic_nav_recents, "Recent apps", new View.OnClickListener() {
                @Override public void onClick(View v) { Tech.touch(NavAccess.this); performGlobalAction(GLOBAL_ACTION_RECENTS); }
            }));
            View div = new View(this);                    // a divider, then Lock in the tonal colour
            div.setBackgroundColor(0xFF40484C);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(1), dp(32));
            dl.leftMargin = dl.rightMargin = dp(4);
            row.addView(div, dl);
            ImageView lk = button(R.drawable.ic_nav_lock, "Lock and return to Salah OS", new View.OnClickListener() {
                @Override public void onClick(View v) { lock(); }
            });
            GradientDrawable lb = new GradientDrawable();
            lb.setColor(0xFF004E60);                      // primary container
            lb.setCornerRadius(dp(28));
            lk.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33B6EAFF), lb, null));
            lk.setColorFilter(0xFFB6EAFF);                // on primary container
            row.addView(lk);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, dp(72),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(20);
            wm.addView(row, lp);
            bar = row;
        } catch (Throwable t) { bar = null; }
    }

    private void hideBar() {
        ui.removeCallbacks(watch);
        try { if (bar != null && wm != null) wm.removeView(bar); } catch (Throwable ignored) { }
        bar = null;
    }

    private void home() {
        performGlobalAction(GLOBAL_ACTION_HOME);
        try { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable ignored) { }
    }

    private void lock() {
        Tech.setLocked(this);
        hideBar();
        home();
    }

    @Override
    public boolean onUnbind(Intent intent) { hideBar(); instance = null; return super.onUnbind(intent); }

    @Override
    public void onDestroy() { hideBar(); instance = null; super.onDestroy(); }
}
