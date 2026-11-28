package com.paltech.salahos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/** Full-screen shell that shows https://app.salahos.com on the prayer frame. */
public class MainActivity extends Activity {
    private static final String HOME_URL = "https://app.salahos.com/";
    /* The address frames used before 1.0.91. The browser keeps each address's saved settings apart,
       so a frame that has not moved yet opens move.html on the old address once: it hands the
       settings over to the new address, and from then on the frame opens the new one directly. */
    private static final String OLD_URL = "https://salah-os.web.app/";
    private static final String PREFS = "frame", MOVED = "moved_to_app_salahos_com";
    private boolean moved() { return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(MOVED, false); }
    private boolean ours(String u) { return u != null && (u.startsWith(HOME_URL) || u.startsWith(OLD_URL)); }
    private static final String OFFLINE_URL = "file:///android_asset/offline.html";
    /* The least the mark is held once the page is ready to be seen. The frame waits for the
       page's first paint rather than for the last of its resources, so this no longer has to
       stand in for a page that is not there yet; anything longer is dead time. */
    private static final long MIN_SPLASH_MS = 120;
    /** How long the page takes to come up out of the black. */
    private static final long FADE_MS = 500;
    /** The mark goes first and quicker, so it is not still there under the page coming up. */
    private static final long MARK_FADE_MS = 260;
    /** The page's own background (--bg in the frame's stylesheet), so the fade lands on it. */
    private static final int PAGE_BG = 0xFFF6F7F8;
    private static final long RETRY_MS = 15000;
    /** How long a frame that has been on Wi-Fi is given to rejoin it at start before saying it cannot. */
    private static final long NET_WAIT_MS = 30000;

    /** The site, told which language the frame was set to, so the wizard does not ask again. */
    private String homeUrl() {
        String base = moved() ? HOME_URL : OLD_URL + "move.html";
        if (host == null) return base;
        String q = "";
        if (host.lang().length() > 0) q = "?lang=" + host.lang();
        if (host.netDone()) q = q.length() > 0 ? q + "&net=1" : "?net=1";
        if (handoff) q = q + (q.length() > 0 ? "&" : "?") + "pair=" + host.pairCode();
        return base + q;
    }

    /** Where the phone goes to carry on, once it is off the frame's hotspot and back on a network. */
    String pairLink() { return "https://salahos.com/s#" + host.pairCode(); }   /* salahos.com/s forwards to app.salahos.com/setup; the code stays on the phone */
    private static final String TECH_URL = "file:///android_asset/tech.html";
    private static final String SELFTEST_URL = "file:///android_asset/selftest.html";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    /* the display's view, for HostControl.snapshot() */
    WebView mainWeb() { return web; }
    private HostControl host;
    private Tech tech;
    static volatile boolean resumed;
    private WebView techView;
    private WebView selfTestView;
    private SelfTest selfTest;
    /* the last good time, once a minute (Clock): restored after a power cut */
    private final Runnable clockTick = new Runnable() {
        @Override public void run() { Clock.tick(MainActivity.this); ui.postDelayed(this, 60L * 1000); }
    };
    static volatile String clockStart = "ok";
    private final Runnable updateTick = new Runnable() {
        @Override public void run() {
            if (host != null) host.autoCheck();
            ui.postDelayed(this, 30L * 60 * 1000);
        }
    };
    private boolean shown;      /* the page has been faded up; it is not done twice */
    private Drawable mark;      /* the window's backing, the mark on black */
    private long startedAt;
    private boolean offline;
    /* True from a failed load of the site until the next attempt starts. WebView reports
       onPageFinished for a page that FAILED too, with the site's own address, straight after
       onReceivedError. Without this that call looked like success: it cleared `offline` while the
       no-internet page was on screen, which silently stopped the retries and made the Wi-Fi
       button on that page do nothing. */
    private boolean loadFailed;
    /** Set while the site is being loaded straight after a phone put this frame on Wi-Fi. */
    private boolean handoff = false;
    
    private final Runnable retry = new Runnable() {
        @Override public void run() {
            /* While the hotspot is up the buyer is in the middle of setting Wi-Fi up on their
               phone, and reloading underneath them would throw that away. The phone's own answer
               brings the site back instead, through onHotspotJoined(). */
            if (!offline || web == null) return;
            /* With nothing carrying traffic there is no point asking for the site, and asking
               would reload the page under someone typing a Wi-Fi password into it. Look again
               later instead. */
            if (host.netBusy() || !hasNetwork()) { ui.postDelayed(this, RETRY_MS); return; }
            loadFailed = false; web.loadUrl(homeUrl());
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        clockStart = Clock.restore(this);
        /* If first setup opened the control channel and the app stopped before closing it, close it now. */
        HostControl.closeSetupChannel(this, getSharedPreferences("frame", MODE_PRIVATE));
        /* A power cut in the first minutes after the app updated itself can leave Android's record of the
           update unwritten, and the frame then starts the image's old app (seen on the dev frame, Nov 2026).
           So once after each new version, write everything to storage. */
        try {
            android.content.SharedPreferences fp = getSharedPreferences("frame", MODE_PRIVATE);
            long code = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
            long had = fp.getLong("synced_code", 0);
            if (had != code) {
                fp.edit().putLong("synced_code", code).commit();
                /* After an update (not the first install) the whole frame restarts once, the way a phone
                   does after a system update, so the new version starts on a clean frame. Storage is
                   written first; the restart comes once that is done. */
                if (had != 0) {
                    if (FrameWifi.isOwner(this)) {
                        HostControl.shellAsync(this, "sync");
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                                    dpm.reboot(new android.content.ComponentName(MainActivity.this, Admin.class));
                                } catch (Throwable t) { android.util.Log.w("SalahOS", "restart after update: " + t); }
                            }
                        }, 8000);
                    } else HostControl.shellAsync(this, "sleep 5; sync; reboot");
                } else HostControl.shellAsync(this, "sleep 20; sync");
            }
        } catch (Throwable ignored) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startedAt = System.currentTimeMillis();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        /* The window's own backing, held so the mark on it can be faded out later. It is the same
           mark on the same black that the starting window, the wallpaper and the boot logo show,
           so the moment the app takes over nothing on screen changes. */
        mark = getDrawable(R.drawable.window_logo);
        if (mark != null) getWindow().setBackgroundDrawable(mark);

        web = new WebView(this);
        /* The page's own colour, not black: the page fades up over the lightening backing, and a
           black sheet behind it while it does would show through as a dark edge. */
        web.setBackgroundColor(PAGE_BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        WebView.setWebContentsDebuggingEnabled(true);
        host = new HostControl(this);
        host.applySaved();
        tech = new Tech(this);
        selfTest = new SelfTest(this);
        web.addJavascriptInterface(host, "SalahFrame");
        web.setWebViewClient(new Client());
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* No splash of its own any more: the mark has been on screen since the frame was switched
           on, and one more copy of it for a moment was not worth the step it made. What is left is
           the window's own backing, which is that same mark on black, so nothing changes at the
           moment the app takes over. Once the page is ready the backing lightens to the page's
           colour and the page fades up through it: the frame is never cut from one screen to
           another, it is only ever lit. */
        web.setAlpha(0f);

        setContentView(root);
        /* A frame that is not on a network yet would otherwise spend the first seconds of its life
           waiting for a name lookup to fail before it could show the one page it already has. The
           wizard is a file in this app, so when there is no network it goes up at once and the
           site is tried again in the background; the frame is on its first screen while it would
           otherwise still have been staring at black. */
        if (hasNetwork()) web.loadUrl(homeUrl());
        else if (host.netDone()) {
            /* A frame that has been on Wi-Fi starts faster than its Wi-Fi does: the app is up a
               few seconds before the radio has rejoined. Going straight to the no-network screens
               then put a list of networks in front of someone whose frame was about to connect by
               itself, and looked exactly like a frame that had forgotten its Wi-Fi. So it waits,
               with the mark still on the screen, and only gives up after long enough that the
               network is really not coming. */
            waitForNetwork(SystemClock.elapsedRealtime() + NET_WAIT_MS);
        } else {
            /* A setup that was begun and never got the frame onto a network starts again from the
               first screen, rather than picking up halfway through with a language somebody chose
               before the frame was unplugged, or moved, or handed to someone else. */
            host.forgetUnfinishedSetup();
            goOffline();
        }
        host.selfTest();
        ui.postDelayed(updateTick, 90 * 1000);
        ui.postDelayed(clockTick, 60L * 1000);
        // Off the critical path: the wallpaper only matters on the next boot, not this one.
        /* The wallpaper is drawn by BootWallpaper now, so nothing is set here. Setting a still
           one needed SET_WALLPAPER_HINTS, which this app does not hold, and it threw every boot. */
        // Keep technician mode's navigation bar switched on across restarts.
        ui.postDelayed(new Runnable() { @Override public void run() { NavAccess.ensure(MainActivity.this); } }, 8000);
        selfTestFrom(getIntent());
    }

    private class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            host.trusted = ours(url);
            host.local = url != null && url.startsWith("file:///android_asset/");
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String u = request.getUrl().toString();
            return !(ours(u) || u.startsWith("file:///android_asset/"));
        }

        /* The first paint, which is when there is something worth showing. onPageFinished waits
           for the last of the page's resources and can be a second or more later; by then the
           frame has been holding the mark over a page that was ready all along. */
        @Override
        public void onPageCommitVisible(WebView view, String url) {
            /* A page out of this app is real whatever the site did; loadFailed only tells us
               the site's own address came back as a failure. */
            if (!loadFailed || (url != null && url.startsWith("file:///android_asset/"))) showPage();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && url.startsWith("https://") && !loadFailed) {
                offline = false;
                /* the new address loaded with the settings handed over: open it directly from now on */
                if (url.startsWith(HOME_URL) && !moved()) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(MOVED, true).apply();
                /* The frame is on the network. The phone is still watching the setup page over
                   the hotspot, so it stays up a little longer to show that it worked. */
                if (host.hotspotUp() && joinedAt == 0) {
                    joinedAt = android.os.SystemClock.elapsedRealtime();
                    ui.removeCallbacks(dropWatch);
                    ui.postDelayed(dropWatch, 3000);
                }
                /* The site has the code now and has opened the session with it; a reload later
                   on is a different occasion and gets a code of its own. */
                if (handoff) { handoff = false; host.pairUsed(); }
            }
            showPage();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) goOffline();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            ui.post(new Runnable() { @Override public void run() { recreate(); } });
            return true;
        }
    }

    private void waitForNetwork(final long until) {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (web == null || isFinishing()) return;
                if (usable()) { loadFailed = false; web.loadUrl(homeUrl()); }
                else if (SystemClock.elapsedRealtime() >= until) { if (hasNetwork()) { loadFailed = false; web.loadUrl(homeUrl()); } else goOffline(); }
                else ui.postDelayed(this, 500);
            }
        }, 500);
    }

    /**
     * Asked for by the frame's own Wi-Fi screen once it has joined a network. Joined is not the
     * same as able to reach anything: the address and the check that there is internet behind it
     * come a second or two later, and asking for the site before then fails and throws the frame
     * back to the screen it just left. So this waits for the network to be usable, and the screen
     * asks again every few seconds, so one call landing early costs nothing.
     */
    void tryHome() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web == null || homing) return;
                homing = true;
                ui.removeCallbacks(retry);
                final long from = SystemClock.elapsedRealtime();
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (web == null || isFinishing()) { homing = false; return; }
                        long waited = SystemClock.elapsedRealtime() - from;
                        if (usable() || (hasNetwork() && waited > 6000)) { homing = false; loadFailed = false; web.loadUrl(homeUrl()); }
                        else if (waited > 15000) homing = false;
                        else ui.postDelayed(this, 400);
                    }
                });
            }
        });
    }
    private boolean homing;

    /** On a network that Android has checked really leads to the internet. */
    private boolean usable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Everything the pages remembered, gone, and the frame back on its first screen. */
    void resetWeb() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    WebStorage.getInstance().deleteAllData();
                    CookieManager.getInstance().removeAllCookies(null);
                    if (web != null) { web.clearCache(true); web.clearHistory(); }
                } catch (Throwable ignored) { }
                recreate();
            }
        });
    }

    /** For the frame reset, which waits for the network it forgot to be gone. */
    boolean online() { return hasNetwork(); }

    /** Whether anything is carrying traffic right now; not whether the site can be reached. */
    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;              /* cannot tell: behave as before */
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;
        }
    }

    private void goOffline() {
        offline = true;
        loadFailed = true;
        ui.removeCallbacks(retry);
        ui.postDelayed(retry, RETRY_MS);
        web.loadUrl(OFFLINE_URL);
    }

    private void showPage() {
        if (shown) return;
        shown = true;
        long wait = Math.max(0, MIN_SPLASH_MS - (System.currentTimeMillis() - startedAt));
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                /* The page comes up out of the black rather than the screen going pale first and
                   the page arriving onto it: there is no moment of bare colour, only the mark
                   giving way to the page. The mark fades quicker than the page rises, so it is
                   gone before the page is transparent enough to show it through. */
                if (mark != null) {
                    ValueAnimator m = ValueAnimator.ofInt(255, 0);
                    m.setDuration(MARK_FADE_MS);
                    m.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(ValueAnimator x) {
                            mark.setAlpha((Integer) x.getAnimatedValue());
                        }
                    });
                    m.start();
                }
                web.animate().alpha(1f).setDuration(FADE_MS).withEndAction(new Runnable() {
                    @Override public void run() {
                        /* Nothing shows through an opaque page, and leaving black behind it would
                           edge every later screen that is not full width. */
                        root.setBackgroundColor(PAGE_BG);
                        getWindow().setBackgroundDrawable(new ColorDrawable(PAGE_BG));
                        mark = null;
                    }
                }).start();
            }
        }, wait);
    }

    /**
     * Technician mode: 5 quick taps in the top corner opposite the Settings gear (within 3 seconds) open the PIN pad.
     * Nothing on screen hints at it.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            Tech.touch(this);
            // The hidden corner is the top corner opposite the Settings gear: top-right, or top-left in Arabic.
            int w = getWindow().getDecorView().getWidth();
            boolean corner = host != null && host.rtl() ? e.getX() < 160 : e.getX() > w - 160;
            if (techView == null && e.getY() < 160 && corner && cornerTaps()) showTech();
        }
        return super.dispatchTouchEvent(e);
    }

    private int tapCount;
    private long tapStart;

    private boolean cornerTaps() {
        long now = System.currentTimeMillis();
        if (now - tapStart > 3000) { tapCount = 0; tapStart = now; }
        if (++tapCount >= 5) { tapCount = 0; return true; }
        return false;
    }

    HostControl host() { return host; }
    boolean isOffline() { return offline; }
    boolean isOnline() { return !offline; }

    private long joinedAt = 0;
    private static final long QUIET_MS = 30000;    /* the phone has stopped asking: it has gone */
    /* Long enough for the buyer to choose a masjid, a photo and a sound on their phone, which is
       what the hotspot is now carrying. The silence timer above is the real guard: a phone that
       walks away takes the hotspot down thirty seconds later whatever this says. */
    private static final long CAP_MS  = 900000;    /* and a hard stop, whatever it is doing */

    /*
      When to take the hotspot down.

      A fixed delay was wrong in both directions. Eight seconds after the join cut the buyer off
      mid-setup - the frame was online, but the phone still had a screen to show and a button to
      offer, and the network it was standing on vanished underneath it. Two minutes was wrong the
      other way, leaving an open access point up long after the phone had finished with it.

      Neither guess was needed: the phone says whether it is still there by asking for things. So
      the hotspot stays while it is being used and goes thirty seconds after the phone falls
      silent - with a hard cap, because a hotspot must never be left running on a wall-mounted
      frame whatever else happens.
    */
    private final Runnable dropWatch = new Runnable() {
        @Override public void run() {
            if (host == null || !host.hotspotUp()) return;
            long sinceJoin = android.os.SystemClock.elapsedRealtime() - joinedAt;
            if (host.hotspotQuietFor() > QUIET_MS || sinceJoin > CAP_MS) { host.netEnd(); return; }
            ui.postDelayed(this, 3000);
        }
    };

    private final Runnable dropHotspot = new Runnable() {
        @Override public void run() { host.netEnd(); }
    };

    /** The phone sent a network and the frame joined it: try the site again straight away. */
    void onHotspotJoined() {
        host.markNetDone();
        handoff = true;
        /* The phone is still sitting on the hotspot, and the page it is holding cannot leave the
           window it is in. Now that the frame has a connection, it carries the phone's traffic
           out through it, so that page reaches the setup session on its own. */
        host.netShare();
        ui.post(new Runnable() {
            @Override public void run() {
                loadFailed = false;
                if (web != null) web.loadUrl(homeUrl());
            }
        });
        /* If the site still will not load, the hotspot has done its job either way and must not
           be left running on a wall-mounted frame. */
        joinedAt = android.os.SystemClock.elapsedRealtime();
        ui.removeCallbacks(dropHotspot);
        ui.removeCallbacks(dropWatch);
        ui.postDelayed(dropWatch, 3000);
    }

    /** Shows the technician page (PIN pad, then the app grid) on top of the display. */
    void showTech() {
        if (techView != null) return;
        try {
            WebView v = new WebView(this);
            v.setBackgroundColor(Color.BLACK);
            v.setOverScrollMode(View.OVER_SCROLL_NEVER);
            WebSettings ts = v.getSettings();
            ts.setJavaScriptEnabled(true);
            ts.setSupportZoom(false);
            v.addJavascriptInterface(tech, "Tech");
            v.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            });
            root.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            techView = v;
            v.loadUrl(TECH_URL);
        } catch (Throwable ignored) { techView = null; }
    }

    void hideTech() {
        if (techView == null) return;
        WebView v = techView;
        techView = null;
        try { root.removeView(v); v.destroy(); } catch (Throwable ignored) { }
    }

    void restartApp() {
        if (web != null) web.clearCache(true);
        recreate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override public void onBackPressed() { /* stay on the display */ }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        web.onResume();
        NavAccess.hide();
        if (Tech.isOpen(this)) { Tech.touch(this); if (techView == null) showTech(); else techView.reload(); }
        else hideTech();
    }

    @Override
    protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); selfTestFrom(intent); }

    /** The flasher asks for a workshop check with an intent extra: selftest = screen, touch, sound or stop. */
    private void selfTestFrom(Intent intent) {
        if (intent == null) return;
        String what = intent.getStringExtra("selftest");
        if (what == null) return;
        intent.removeExtra("selftest");
        if ("touch".equals(what) || "sound".equals(what) || "screen".equals(what)) showSelfTest(what, intent.getStringExtra("nonce"));
        else hideSelfTest();
    }

    void showSelfTest(String mode, String nonce) {
        hideSelfTest();
        try {
            selfTest.begin(nonce);
            WebView v = new WebView(this);
            v.setBackgroundColor(Color.BLACK);
            v.setOverScrollMode(View.OVER_SCROLL_NEVER);
            WebSettings ts = v.getSettings();
            ts.setJavaScriptEnabled(true);
            ts.setSupportZoom(false);
            v.addJavascriptInterface(selfTest, "SelfTest");
            v.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            });
            root.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            selfTestView = v;
            v.loadUrl(SELFTEST_URL + "#" + mode);
        } catch (Throwable ignored) { selfTestView = null; }
    }

    void hideSelfTest() {
        if (selfTest != null) selfTest.stopSound();
        if (selfTestView == null) return;
        WebView v = selfTestView;
        selfTestView = null;
        try { root.removeView(v); v.destroy(); } catch (Throwable ignored) { }
    }
    @Override protected void onPause() { resumed = false; web.onPause(); super.onPause(); }

    @Override
    protected void onDestroy() {
        hideSelfTest();
        ui.removeCallbacksAndMessages(null);
        /* A hotspot is a setup tool, never something left running behind a closed app. */
        if (host != null) host.netEnd();
        hideTech();
        if (web != null) { root.removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
