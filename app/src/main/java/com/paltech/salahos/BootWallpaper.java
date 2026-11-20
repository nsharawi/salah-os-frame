package com.paltech.salahos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.content.Context;
import android.graphics.Point;
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
                /* The wallpaper surface is not always given in the orientation the frame is read
                   in. Rather than trust the display's rotation value - which says this panel is
                   turned 90 degrees whether or not the surface already accounts for it - compare
                   the shape of the surface with the shape of the display. They disagree only when
                   the surface is still in the panel's own orientation, and that is the one case
                   that needs turning. A square surface matches either way and is left alone. */
                final int deg = turnNeeded(w, h);
                if (deg != 0) c.rotate(deg, w / 2f, h / 2f);

                c.drawBitmap(art, box, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                Log.i(TAG, "drew the boot wallpaper: surface " + w + "x" + h + ", display "
                        + displaySize() + ", turned " + deg + " deg, logo " + dw + "x" + dh);
            } catch (Throwable t) {
                /* Cosmetic: a frame that cannot draw this still boots and runs. */
                Log.w(TAG, "could not draw the boot wallpaper: " + t);
            } finally {
                if (c != null) { try { holder.unlockCanvasAndPost(c); } catch (Throwable ignored) { } }
                if (art != null) art.recycle();
            }
        }

        /** The display's own size, as width x height. */
        private Point display() {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                Display d = wm == null ? null : wm.getDefaultDisplay();
                if (d == null) return null;
                Point p = new Point();
                d.getRealSize(p);
                return p.x > 0 && p.y > 0 ? p : null;
            } catch (Throwable t) {
                Log.w(TAG, "could not read the display size: " + t);
                return null;
            }
        }

        private String displaySize() {
            Point p = display();
            return p == null ? "unknown" : (p.x + "x" + p.y);
        }

        /** 90 when the surface is still in the panel's orientation, 0 when it already matches. */
        private int turnNeeded(int w, int h) {
            Point p = display();
            if (p == null || w == h || p.x == p.y) return 0;
            return (w > h) == (p.x > p.y) ? 0 : 90;
        }
    }
}
