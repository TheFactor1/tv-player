plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.thefactor1.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thefactor1.player"
        minSdk = 24
        targetSdk = 36
        versionCode = 47
        versionName = "0.9.35"

        // X-Ray's cast comes from TMDB; CI passes the key from the TMDB_API_KEY secret. Without one, X-Ray stays off.
        buildConfigField("String", "TMDB_API_KEY", "\"${System.getenv("TMDB_API_KEY").orEmpty().trim()}\"")

        // TV boxes are ARM; x86_64 keeps the PC emulator working. Dropping 32-bit x86 trims the FFmpeg libraries.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // Sideload builds: sign with a real key when CI provides one, otherwise the debug key.
        create("release") {
            val path = System.getenv("SIGNING_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
