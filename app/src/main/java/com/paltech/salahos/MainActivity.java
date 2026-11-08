package com.paltech.salahos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private ImageView splash;
    private long startedAt;
    private boolean offline;
    private int taps;
    private long firstTap;

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
    }

    private class Client extends WebViewClient {
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

    /** Maintenance exit: tap the top-left corner 5 times within 4 seconds to open Android Settings. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && e.getX() < 160 && e.getY() < 160) {
            long now = System.currentTimeMillis();
            if (now - firstTap > 4000) { taps = 0; firstTap = now; }
            if (++taps >= 5) {
                taps = 0;
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            }
        }
        return super.dispatchTouchEvent(e);
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

    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onPause() { web.onPause(); super.onPause(); }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) { root.removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
