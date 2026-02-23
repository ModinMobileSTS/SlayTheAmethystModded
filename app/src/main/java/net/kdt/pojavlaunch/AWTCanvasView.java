/*
 * Derived from PojavLauncher project sources.
 * Source: https://github.com/AngelAuraMC/Amethyst-Android (branch: v3_openjdk)
 * License: LGPL-3.0
 * Modifications: adapted for the SlayTheAmethystModded Android integration.
 */

package net.kdt.pojavlaunch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

import net.kdt.pojavlaunch.utils.JREUtils;

public class AWTCanvasView extends TextureView implements TextureView.SurfaceTextureListener, Runnable {
    public static final int AWT_CANVAS_WIDTH = 720;
    public static final int AWT_CANVAS_HEIGHT = 600;

    private volatile boolean destroyed = false;

    public AWTCanvasView(Context context) {
        this(context, null);
    }

    public AWTCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSurfaceTextureListener(this);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        getSurfaceTexture().setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
        destroyed = false;
        new Thread(this, "AndroidAWTRenderer").start();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        destroyed = true;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        getSurfaceTexture().setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    public void run() {
        Surface surface = new Surface(getSurfaceTexture());
        Bitmap bitmap = Bitmap.createBitmap(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);

        while (!destroyed && surface.isValid()) {
            int[] pixels = JREUtils.renderAWTScreenFrame();
            if (pixels != null) {
                bitmap.setPixels(pixels, 0, AWT_CANVAS_WIDTH, 0, 0, AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
            }
            Canvas canvas = surface.lockCanvas(null);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(bitmap, 0, 0, paint);
            surface.unlockCanvasAndPost(canvas);
        }

        bitmap.recycle();
        surface.release();
    }
}
