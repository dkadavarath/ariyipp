import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
// No google-services plugin: Firebase is initialized at RUNTIME from a user-imported
// google-services.json (Pairing screen -> Import Firebase files), not baked in at build time. This
// lets the app build from source with no Firebase project of the maintainer's baked in.

// Release signing is configured only when keystore.properties exists (kept out of VCS).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.noti.logger"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.noti.logger"
        minSdk = 26
        targetSdk = 35
        // versionCode lives in its own range, kept above every build the private dev repo has made
        // (300+ from v1.4), so a public release always installs as an upgrade and the two never
        // collide. Bump by at least 1 each release; versionName is this public repo's own v1.x
        // sequence.
        versionCode = 300
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // The app's own text is English-only; without this, every library's translations (80+
        // locales of Material/AppCompat strings) ship anyway - most of resources.arsc - and a
        // non-English phone gets a mix of English screens and translated library dialogs.
        // (localeFilters replaces defaultConfig.resourceConfigurations, gone in AGP 9's DSL.)
        localeFilters += listOf("en")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin is built into AGP 9: jvmTarget follows compileOptions.targetCompatibility, and
    // src/<set>/kotlin is a default source root, so no kotlinOptions / sourceSets needed.
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.core.splashscreen)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Security
    implementation(libs.androidx.security.crypto)

    // Firebase Cloud Messaging (inbound push → local notification)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Shared crypto + wire format (used by this app and the sender app)
    implementation(project(":shared"))

    // Companion (SMS-source) role, as a library; its manifest components + permissions merge in.
    implementation(project(":sender"))

    // QR generation + scanning for pairing (embedded bundles zxing core)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Unit tests
    testImplementation(libs.junit)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
}
