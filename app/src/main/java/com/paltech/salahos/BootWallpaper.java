package com.paltech.salahos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.content.Context;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

/**
 * The Salah OS logo on black, drawn as the system wallpaper by the app itself.
 *
 * This frame ships with Android's System UI switched off, and the stock wallpaper is drawn by
 * System UI. With nothing able to draw one, Android waits at the end of every boot for a
 * wallpaper that never comes, gives up after 30 seconds, and only then starts the home app -
 * so the frame sat on the boot logo for half a minute longer than it needed to. The placeholder
 * home screen it shows in between (com.android.settings.FallbackHome) is also nothing but the
 * wallpaper, so without one that moment is black.
 *
 * A wallpaper the app draws needs no System UI. Android gets its wallpaper at once, the wait
 * goes, and the placeholder shows the same mark at the same size as the boot logo before it and
 * the splash after it.
 *
 * It is marked directBootAware because Android asks for the wallpaper before the user's storage
 * is unlocked. It reads nothing but a drawable from the app itself, so that is safe.
 *
 * Android only uses it once it is named as the wallpaper component: default_wallpaper_component
 * in the image's framework overlay, or a one-off command on a development frame.
 */
public class BootWallpaper extends WallpaperService {
    private static final String TAG = "SalahWallpaper";

    @Override public Engine onCreateEngine() { return new LogoEngine(); }

    private final class LogoEngine extends Engine {
        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            draw(holder);
        }

        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            draw(holder);
        }

        @Override public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) draw(getSurfaceHolder());
        }

        private void draw(SurfaceHolder holder) {
            if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) return;
            Canvas c = null;
            Bitmap art = null;
            try {
                c = holder.lockCanvas();
                if (c == null) return;
                c.drawColor(Color.BLACK);

                art = BitmapFactory.decodeResource(getResources(), R.drawable.splash_full);
                if (art == null) { Log.w(TAG, "splash_full could not be decoded"); return; }

                final int w = c.getWidth(), h = c.getHeight();

                /* The wallpaper surface is in the panel's own orientation, and this panel is
                   physically portrait while the frame is read in landscape. Drawn straight onto
                   the surface the mark comes out on its side, so the canvas is turned by the same
                   amount the display is, about its centre. The surface is square here, so turning
                   it about the centre keeps the mark centred whatever the rotation is. */
                final int deg = rotationDegrees();
                if (deg != 0) c.rotate(deg, w / 2f, h / 2f);

                /* Same rule as the boot logo and the splash: the mark is a fixed share of the
                   short side, centred. The short side, so a surface handed over before the
                   display has turned cannot make the logo bigger. */
                Rect box = Wallpaper.contentBox(art);
                float targetH = Math.min(w, h) * Wallpaper.LOGO_SHARE;
                float scale = targetH / box.height();
                int dw = Math.round(box.width() * scale), dh = Math.round(targetH);
                Rect dst = new Rect((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
                c.drawBitmap(art, box, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                Log.i(TAG, "drew the boot wallpaper: surface " + w + "x" + h + ", turned " + deg
                        + " deg, logo " + dw + "x" + dh);
            } catch (Throwable t) {
                /* Cosmetic: a frame that cannot draw this still boots and runs. */
                Log.w(TAG, "could not draw the boot wallpaper: " + t);
            } finally {
                if (c != null) { try { holder.unlockCanvasAndPost(c); } catch (Throwable ignored) { } }
                if (art != null) art.recycle();
            }
        }

        /** How far the display is turned from the panel's own orientation, in degrees. */
        private int rotationDegrees() {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return 0;
                Display d = wm.getDefaultDisplay();
                if (d == null) return 0;
                switch (d.getRotation()) {
                    case Surface.ROTATION_90:  return 90;
                    case Surface.ROTATION_180: return 180;
                    case Surface.ROTATION_270: return 270;
                    default: return 0;
                }
            } catch (Throwable t) {
                Log.w(TAG, "could not read the display rotation: " + t);
                return 0;
            }
        }
    }
}
