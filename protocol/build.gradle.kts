plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "dev.localstream.protocol"
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
