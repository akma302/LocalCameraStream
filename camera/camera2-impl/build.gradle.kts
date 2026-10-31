plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.localstream.camera.camera2impl"
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":camera:api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
