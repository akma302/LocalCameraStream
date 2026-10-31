plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.localstream.camera.api"
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
