import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val fritzboxHost = (project.findProperty("fritzboxHost") as String?) ?: "192.168.200.1"

android {
    namespace = "de.thecraftedtech.hometv"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "de.thecraftedtech.hometv"
        minSdk = 22          // Fire OS 5 (Android 5.1) und neuer
        targetSdk = 34
        versionCode = 6
        versionName = "1.4.0"

        buildConfigField("String", "FRITZBOX_HOST", "\"$fritzboxHost\"")

        // Fire TV Sticks sind ARM. x86 wird fuer die APK nicht benoetigt (spart ~50 MB).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile").removePrefix("../"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false      // LibVLC nutzt JNI; fuer den Prototyp kein R8
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // android.util.Log & Co. liefern in JVM-Tests Standardwerte (der Live-Test nutzt SatIpSession)
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

// Senderliste als Rueckfallkopie mitliefern: tests/data/tv.m3u (wenn vorhanden) -> assets/tv.m3u.
// Die App laedt die Liste zuerst live von der FRITZ!Box; diese Kopie wird nur genutzt, wenn die Box nicht erreichbar ist.
val copyChannelList = tasks.register<Copy>("copyChannelList") {
    from(rootProject.file("tests/data/tv.m3u"))
    into(layout.buildDirectory.dir("generated/hometv-assets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/hometv-assets"))
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("lint", ignoreCase = true)
}.configureEach { dependsOn(copyChannelList) }

dependencies {
    // Offizielle LibVLC-Android-Bibliothek (LGPL 2.1), stabile 3.x-Reihe
    implementation("org.videolan.android:libvlc-all:3.7.7")

    testImplementation("junit:junit:4.13.2")
}
