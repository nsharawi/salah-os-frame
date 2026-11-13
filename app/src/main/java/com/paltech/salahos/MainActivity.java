package com.paltech.salahos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Full-screen shell that shows https://salah-os.web.app on the prayer frame. */
public class MainActivity extends Activity {
    private static final String HOME_URL = "https://salah-os.web.app/";
    private static final String OFFLINE_URL = "file:///android_asset/offline.html";
    /* Just long enough for the page to have painted; the half-second fade covers the rest.
       Any longer is dead time the frame spends showing a logo it no longer needs to. */
    private static final long MIN_SPLASH_MS = 400;
    private static final long RETRY_MS = 15000;

    /** The site, told which language the frame was set to, so the wizard does not ask again. */
    private String homeUrl() {
        if (host == null) return HOME_URL;
        String q = "";
        if (host.lang().length() > 0) q = "?lang=" + host.lang();
        if (host.netDone()) q = q.length() > 0 ? q + "&net=1" : "?net=1";
        if (handoff) q = q + (q.length() > 0 ? "&" : "?") + "pair=" + host.pairCode();
        return HOME_URL + q;
    }

    /** Where the phone goes to carry on, once it is off the frame's hotspot and back on a network. */
    String pairLink() { return HOME_URL + "setup#" + host.pairCode(); }
    private static final String TECH_URL = "file:///android_asset/tech.html";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private HostControl host;
    private Tech tech;
    static volatile boolean resumed;
    private WebView techView;
    private final Runnable updateTick = new Runnable() {
        @Override public void run() {
            if (host != null) host.autoCheck();
            ui.postDelayed(this, 30L * 60 * 1000);
        }
    };
    private ImageView splash;
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
            if (offline && web != null && !host.netBusy()) { loadFailed = false; web.loadUrl(homeUrl()); }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startedAt = System.currentTimeMillis();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
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
        web.addJavascriptInterface(host, "SalahFrame");
        web.setWebViewClient(new Client());
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splash = new ImageView(this);
        splash.setBackgroundColor(Color.BLACK);
        splash.setScaleType(ImageView.ScaleType.FIT_CENTER);
        splash.setImageResource(R.drawable.splash_full);
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        web.loadUrl(homeUrl());
        host.selfTest();
        ui.postDelayed(updateTick, 90 * 1000);
        // Off the critical path: the wallpaper only matters on the next boot, not this one.
        ui.postDelayed(new Runnable() { @Override public void run() { Wallpaper.applyOnce(MainActivity.this); } }, 12000);
        // Keep technician mode's navigation bar switched on across restarts.
        ui.postDelayed(new Runnable() { @Override public void run() { NavAccess.ensure(MainActivity.this); } }, 8000);
    }

    private class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            host.trusted = url != null && url.startsWith(HOME_URL);
            host.local = url != null && url.startsWith("file:///android_asset/");
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String u = request.getUrl().toString();
            return !(u.startsWith(HOME_URL) || u.startsWith("file:///android_asset/"));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && url.startsWith("https://") && !loadFailed) {
                offline = false;
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
            hideSplash();
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

    private void goOffline() {
        offline = true;
        loadFailed = true;
        ui.removeCallbacks(retry);
        ui.postDelayed(retry, RETRY_MS);
        web.loadUrl(OFFLINE_URL);
    }

    private void hideSplash() {
        if (splash == null) return;
        final ImageView v = splash;
        splash = null;
        long wait = Math.max(0, MIN_SPLASH_MS - (System.currentTimeMillis() - startedAt));
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                v.animate().alpha(0f).setDuration(500).withEndAction(new Runnable() {
                    @Override public void run() { root.removeView(v); }
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
    protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); }
    @Override protected void onPause() { resumed = false; web.onPause(); super.onPause(); }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        /* A hotspot is a setup tool, never something left running behind a closed app. */
        if (host != null) host.netEnd();
        hideTech();
        if (web != null) { root.removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
