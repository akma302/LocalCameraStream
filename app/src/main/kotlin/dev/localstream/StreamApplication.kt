package dev.localstream

import android.app.Application
import dev.localstream.camera.api.CameraApi
import dev.localstream.camera.camera2.Camera2Api
import dev.localstream.camera.ndk.NdkCameraApi
import dev.localstream.server.HostController

class StreamApplication : Application() {
    val host by lazy { HostController(this) }
    fun camera(backend: String): CameraApi = when (backend) {
        "ndk" -> NdkCameraApi()
        else -> Camera2Api(this)
    }
}
