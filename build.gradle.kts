import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 37
            defaultConfig { minSdk = 33; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
            compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = 37
            defaultConfig { minSdk = 33; targetSdk = 37 }
            compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
        }
    }
}
