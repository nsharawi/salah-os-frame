package com.paltech.salahos;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

/**
 * Sets the system wallpaper to the Salah OS splash.
 *
 * Nothing on the frame shows the wallpaper during normal use — Salah OS covers the screen. It
 * matters for the few seconds in the middle of a boot: once the boot animation stops, Android puts
 * up a placeholder home screen (com.android.settings.FallbackHome) while the real launcher starts,
 * and that placeholder draws nothing but the wallpaper. Out of the box that is ONN's blue, so the
 * frame flashes someone else's branding between our boot logo and our app.
 *
 * The size needs care. Android asks for a wallpaper wider than the display so it can pan behind
 * home screens, and it scales whatever it is given up to that size — hand it a screen-sized image
 * and the logo comes out visibly bigger than the one in the boot logo and the splash. So the
 * bitmap is built at the size the system asks for, with the logo drawn to a fraction of the
 * SCREEN height rather than the canvas height. Then all three show the mark at the same size.
 *
 * Done once per install, and a wallpaper the user has chosen themselves is left alone.
 */
final class Wallpaper {
    private static final String TAG = "SalahWallpaper";
    private static final String PREFS = "salahos";
    private static final String DONE = "wallpaper_set_v2";   // v1 sized it wrongly; redo it once

    /** Logo height as a share of the screen, matching splash_full.png and the boot logo. */
    private static final float LOGO_SHARE = 0.506f;

    private Wallpaper() {}

    static void applyOnce(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(DONE, false)) return;

        Bitmap art = null, out = null;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            if (wm == null) return;

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager w = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (w == null) return;
            w.getDefaultDisplay().getRealMetrics(dm);
            final int sw = dm.widthPixels, sh = dm.heightPixels;
            if (sw <= 0 || sh <= 0) return;

            /* Ask for a wallpaper exactly the size of the screen. The system may still want more,
               so whatever it reports back is what we actually draw. */
            wm.suggestDesiredDimensions(sw, sh);
            int cw = Math.max(wm.getDesiredMinimumWidth(), sw);
            int ch = Math.max(wm.getDesiredMinimumHeight(), sh);
            /* Guard against an absurd request on an odd device. */
            if (cw > sw * 4) cw = sw * 2;
            if (ch > sh * 4) ch = sh * 2;

            art = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.splash_full);
            if (art == null) { Log.w(TAG, "splash_full could not be decoded"); return; }

            /* The artwork is the logo on black with generous margins; take just the logo out of it
               by measuring where the non-black pixels are, so the margins do not throw the scale. */
            Rect box = contentBox(art);
            float targetH = sh * LOGO_SHARE;
            float scale = targetH / box.height();
            int dw = Math.round(box.width() * scale), dh = Math.round(targetH);

            out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(Color.BLACK);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            Rect dst = new Rect((cw - dw) / 2, (ch - dh) / 2, (cw - dw) / 2 + dw, (ch - dh) / 2 + dh);
            c.drawBitmap(art, box, dst, paint);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                wm.setBitmap(out, null, true, WallpaperManager.FLAG_SYSTEM);
            } else {
                wm.setBitmap(out);
            }

            p.edit().putBoolean(DONE, true).apply();
            Log.i(TAG, "wallpaper set: canvas " + cw + "x" + ch + ", screen " + sw + "x" + sh + ", logo " + dw + "x" + dh);
        } catch (Throwable t) {
            /* A frame that cannot set its wallpaper still works perfectly; this is cosmetic.
               Record it and carry on rather than letting it affect startup. */
            Log.w(TAG, "could not set the wallpaper: " + t);
        } finally {
            if (out != null) out.recycle();
            if (art != null) art.recycle();
        }
    }

    /** The smallest rectangle holding everything that is not the black background. */
    private static Rect contentBox(Bitmap b) {
        final int w = b.getWidth(), h = b.getHeight(), step = Math.max(1, Math.min(w, h) / 400);
        int l = w, t = h, r = -1, bo = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y += step) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += step) {
                int px = row[x];
                if (((px >> 16) & 255) > 24 || ((px >> 8) & 255) > 24 || (px & 255) > 24) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > bo) bo = y;
                }
            }
        }
        if (r < 0) return new Rect(0, 0, w, h);          /* all black: fall back to the whole image */
        return new Rect(l, t, Math.min(w, r + 1), Math.min(h, bo + 1));
    }
}
