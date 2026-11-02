plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.localstream.server"
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    api(project(":video:api"))
    implementation(project(":camera:api"))
    implementation(project(":protocol"))
    implementation(project(":network"))
    implementation(project(":media"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.ktor:ktor-server-netty:3.4.3")
    implementation("io.ktor:ktor-server-websockets:3.4.3")
}
