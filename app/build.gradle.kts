plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.localstream.app"
    defaultConfig { applicationId = "dev.localstream"; versionCode = 1; versionName = "0.1"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties") }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(project(":camera:api"))
    implementation(project(":camera:camera2-impl"))
    implementation(project(":camera:ndk-impl"))
    implementation(project(":server"))
    implementation(project(":client:api"))
    implementation(project(":client:impl"))
    implementation(project(":client:ui"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
}
