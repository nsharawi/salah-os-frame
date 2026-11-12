package com.paltech.salahos;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

/**
 * Sets the system wallpaper to the Salah OS splash.
 *
 * Nothing on the frame ever shows the wallpaper during normal use — Salah OS covers the screen.
 * It matters for the few seconds in the middle of a boot: once the boot animation stops, Android
 * puts up a placeholder home screen (com.android.settings.FallbackHome) while the real launcher
 * starts, and that placeholder draws nothing but the wallpaper. Out of the box that is ONN's blue,
 * so the frame flashes someone else's branding between our boot logo and our app. Making the
 * wallpaper the same image as the splash closes that gap.
 *
 * Done once per install. If the user ever changes it themselves we leave their choice alone.
 */
final class Wallpaper {
    private static final String TAG = "SalahWallpaper";
    private static final String PREFS = "salahos";
    private static final String DONE = "wallpaper_set_v1";

    private Wallpaper() {}

    static void applyOnce(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(DONE, false)) return;

        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            if (wm == null) return;

            Bitmap bmp = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.splash_full);
            if (bmp == null) { Log.w(TAG, "splash_full could not be decoded"); return; }

            /* FLAG_SYSTEM is the home-screen wallpaper, which is the one the placeholder draws.
               The older setBitmap covers both on pre-N and is the safe fallback. */
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM);
            } else {
                wm.setBitmap(bmp);
            }

            p.edit().putBoolean(DONE, true).apply();
            Log.i(TAG, "wallpaper set to the Salah OS splash");
        } catch (Throwable t) {
            /* A frame that cannot set its wallpaper still works perfectly; it is cosmetic.
               Record it and carry on rather than letting it affect startup. */
            Log.w(TAG, "could not set the wallpaper: " + t);
        }
    }
}
