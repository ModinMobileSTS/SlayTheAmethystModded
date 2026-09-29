package io.stamethyst.backend.launch

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * A standalone render target for the game, held without any display, window, or Activity.
 *
 * The game JVM only needs one thing from the Android side: a valid native window handed to
 * `JREUtils.setupBridgeWindow`. An [ImageReader]'s [Surface] is such a window, and it belongs to no
 * display, so the game renders normally while nothing appears on the user's screen and the launcher
 * keeps the foreground.
 *
 * A display-based variant was tried first and rejected by the platform: launching an Activity onto an
 * app-created virtual display requires `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`/`PUBLIC`, which are
 * signature-protected, so `startActivity` failed with `Permission Denial ... with launchDisplayId`.
 * Rendering into an ImageReader the app owns has no such restriction.
 *
 * The queue must be drained continuously: once it holds [MAX_IMAGES] frames the producer blocks, and
 * the game would stall in `eglSwapBuffers`. [ImageReader] keeps only its newest frames, so dropping
 * each acquired image is exactly the desired behaviour for an invisible run.
 */
internal class HeadlessGameSurface private constructor(
    private val imageReader: ImageReader,
    private val drainThread: HandlerThread,
    val surface: Surface,
    val width: Int,
    val height: Int,
) {
    fun release() {
        drainThread.quitSafely()
        runCatching { surface.release() }
            .onFailure { Log.w(TAG, "Unable to release the headless surface", it) }
        runCatching { imageReader.close() }
            .onFailure { Log.w(TAG, "Unable to close the headless image reader", it) }
    }

    companion object {
        private const val TAG = "HeadlessSurface"
        private const val MAX_IMAGES = 3

        /**
         * Creates the render target at the given size.
         *
         * The caller matches the physical panel's resolution so the game's window sizing and display
         * config stay identical to a normal launch and the verdict is not distorted by the harness.
         */
        fun create(width: Int, height: Int): HeadlessGameSurface? {
            val safeWidth = width.coerceAtLeast(1)
            val safeHeight = height.coerceAtLeast(1)
            val drainThread = HandlerThread("AmethystHeadlessSurface")
            drainThread.start()

            val imageReader = runCatching { newImageReader(safeWidth, safeHeight) }
                .getOrElse { error ->
                    Log.w(TAG, "Unable to create the headless image reader", error)
                    drainThread.quitSafely()
                    return null
                }
            val surface = runCatching { imageReader.surface }
                .onFailure { Log.w(TAG, "Unable to obtain the headless surface", it) }
                .getOrNull()
            if (surface == null || !surface.isValid) {
                Log.w(TAG, "Headless surface is not valid")
                runCatching { surface?.release() }
                runCatching { imageReader.close() }
                drainThread.quitSafely()
                return null
            }
            imageReader.setOnImageAvailableListener({ reader ->
                runCatching { reader.acquireLatestImage()?.close() }
            }, Handler(drainThread.looper))
            Log.i(TAG, "Headless render target ready size=${safeWidth}x$safeHeight")
            return HeadlessGameSurface(imageReader, drainThread, surface, safeWidth, safeHeight)
        }

        /**
         * Builds an ImageReader explicitly usable as a GPU render target.
         *
         * Without `USAGE_GPU_COLOR_OUTPUT`, `eglCreateWindowSurface` on the reader's surface can be
         * rejected and the renderer would never produce a frame. The usage-aware factory exists from
         * API 29; below that the reader defaults are the only option.
         */
        @Suppress("DEPRECATION")
        private fun newImageReader(width: Int, height: Int): ImageReader {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    MAX_IMAGES,
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN,
                )
            }
            // ImageFormat.RGBA_8888 and PixelFormat.RGBA_8888 are the same value; ImageFormat is
            // the constant ImageReader documents, so use it below API 29.
            return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        }
    }
}
