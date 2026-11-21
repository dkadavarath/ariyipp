plugins {
    // No version: AGP is already on the classpath from :app's application plugin.
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// Companion (SMS-source) role of ariyipp, packaged as a library the app module includes. Its
// manifest components + permissions merge into the app; the app owns Firebase, signing, and the
// single launcher.
android {
    namespace = "com.noti.sender"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Built-in Kotlin (AGP 9): jvmTarget follows compileOptions; src/<set>/kotlin is a default root.
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    // Firebase Cloud Messaging (the app module supplies the google-services config at runtime).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Shared crypto + wire format
    implementation(project(":shared"))

    // QR scanner for pairing
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
