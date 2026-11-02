plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.localstream.media"
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(project(":video:api"))
    api("io.github.webrtc-sdk:android:150.7871.01")
    implementation(project(":camera:api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
