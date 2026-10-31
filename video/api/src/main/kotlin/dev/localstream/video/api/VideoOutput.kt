package dev.localstream.video.api

import android.content.Context
import android.view.View

/** UI sees Android Views, never WebRTC tracks, sinks, native handles or frame buffers. */
interface VideoOutput {
    fun createView(context: Context): View
    fun releaseView(view: View)
}
