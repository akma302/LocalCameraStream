package dev.localstream.video.api

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.StateFlow

/** UI sees Android Views, never WebRTC tracks, sinks, native handles or frame buffers. */
interface VideoOutput {
    /** Width / height after frame rotation. Updates without recreating the renderer. */
    val aspectRatio: StateFlow<Float>
    fun createView(context: Context): View
    fun releaseView(view: View)
}
