plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.localstream.client.impl"
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":client:api"))
    implementation(project(":protocol"))
    implementation(project(":network"))
    implementation(project(":media"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.ktor:ktor-client-okhttp:3.4.3")
    implementation("io.ktor:ktor-client-websockets:3.4.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
