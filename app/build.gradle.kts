plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun envOr(key: String, default: String): String =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

val runNumber = envOr("GITHUB_RUN_NUMBER", "1").toIntOrNull() ?: 1

android {
    namespace = "com.jarvis.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = file(envOr("KEYSTORE_PATH", "../signing/jarvis.jks"))
            storePassword = envOr("KEYSTORE_PASSWORD", "JarvisSign2026x")
            keyAlias = envOr("KEY_ALIAS", "jarvis")
            keyPassword = envOr("KEY_PASSWORD", "JarvisSign2026x")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
