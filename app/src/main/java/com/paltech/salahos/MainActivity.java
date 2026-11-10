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
    private static final long MIN_SPLASH_MS = 1800;
    private static final long RETRY_MS = 15000;
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
    
    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (offline && web != null) web.loadUrl(HOME_URL);
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
        web.loadUrl(HOME_URL);
        host.selfTest();
        ui.postDelayed(updateTick, 90 * 1000);
        // Keep technician mode's navigation bar switched on across restarts.
        ui.postDelayed(new Runnable() { @Override public void run() { NavAccess.ensure(MainActivity.this); } }, 8000);
    }

    private class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            host.trusted = url != null && url.startsWith(HOME_URL);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String u = request.getUrl().toString();
            return !(u.startsWith(HOME_URL) || u.startsWith("file:///android_asset/"));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && url.startsWith("https://")) offline = false;
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
        hideTech();
        if (web != null) { root.removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
