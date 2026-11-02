package dev.localstream.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.Display
import android.view.Surface

/** Lives with capture, including foreground streaming without an Activity.
 * Display callbacks also cover 180-degree changes without Activity recreation. */
internal class DisplayRotationTracker(context: Context, private val handler: Handler) : DisplayManager.DisplayListener {
    private val manager = context.getSystemService(DisplayManager::class.java)
    private var registered = false
    @Volatile var degrees: Int = 0
        private set

    fun start() {
        check(!registered)
        manager.registerDisplayListener(this, handler)
        registered = true
        refresh()
    }
    fun close() {
        if (registered) { manager.unregisterDisplayListener(this); registered = false }
    }
    private fun refresh() {
        degrees = when (manager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
    override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) refresh() }
    override fun onDisplayAdded(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) refresh() }
    override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) refresh() }
}
