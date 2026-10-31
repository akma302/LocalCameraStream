plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.localstream.server"
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":video:api"))
    implementation(project(":camera:api"))
    implementation(project(":protocol"))
    implementation(project(":network"))
    implementation(project(":media"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.ktor:ktor-server-cio:3.4.3")
    implementation("io.ktor:ktor-server-websockets:3.4.3")
}
