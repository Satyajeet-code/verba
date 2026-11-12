import java.net.URI

plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

// ---------------------------------------------------------------------------
// sherpa-onnx (ONNX Runtime + the speech recognisers) comes as a prebuilt AAR
// from its GitHub release. It is downloaded once into app/libs/ (git-ignored).
// The speech MODELS are not bundled: the app downloads them on first use.
// ---------------------------------------------------------------------------
val sherpaOnnxVersion = "2.0.1"

fun githubAar(fileName: String, url: String): File {
    val dir = file("libs")
    val target = File(dir, fileName)
    if (!target.isFile || target.length() < 100_000) {
        dir.mkdirs()
        logger.lifecycle("Downloading $fileName")
        val part = File(dir, "$fileName.part")
        URI(url).toURL().openStream().use { input ->
            part.outputStream().use { input.copyTo(it) }
        }
        if (!part.renameTo(target)) throw GradleException("Could not write $target")
    }
    return target
}

android {
    namespace = "com.verba.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.verba.app"
        // Android 12+: needed for real background blur, window blur-behind and
        // the resizable widget APIs the glass design uses.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Phones. Add "x86_64" to also run on a PC emulator (bigger APK).
            abiFilters += listOf("arm64-v8a")
        }
    }

    // A fixed key, so every build installs as an update over the previous
    // one. Override with your own keystore through these environment variables.
    signingConfigs {
        create("sideload") {
            storeFile = file(System.getenv("VERBA_KEYSTORE") ?: "verba.jks")
            storePassword = System.getenv("VERBA_KEYSTORE_PASSWORD") ?: "verbaapp"
            keyAlias = System.getenv("VERBA_KEY_ALIAS") ?: "verba"
            keyPassword = System.getenv("VERBA_KEY_PASSWORD") ?: "verbaapp"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    // Personal sideload app: don't let Play Store release checks block the build.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

chaquopy {
    defaultConfig {
        // 3.13+ is needed for Android 15's 16 KB memory pages.
        version = "3.13"
        // The backend uses only the standard library (sqlite3, tarfile, urllib…).
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    implementation(
        files(
            githubAar(
                "sherpa-onnx-$sherpaOnnxVersion.aar",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                    "v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar",
            )
        )
    )
}
