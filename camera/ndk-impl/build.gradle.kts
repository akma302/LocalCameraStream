plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.localstream.camera.ndkimpl"
    ndkVersion = "27.0.12077973"
    defaultConfig { ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") } }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":camera:api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
