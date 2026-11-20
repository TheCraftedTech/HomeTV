plugins {
    id("com.android.application") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}

// Das Verzeichnis build/ im Projektstamm ist fuer die fertige APK (build/HomeTV.apk) reserviert.
// Der Root-Build schreibt deshalb woanders hin, damit "gradlew clean" die APK nicht loescht.
layout.buildDirectory.set(file(".gradle/root-build"))
