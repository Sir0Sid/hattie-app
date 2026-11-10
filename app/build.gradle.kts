plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pro.hattie.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "pro.hattie.app"
        /* 26 is Android 8: adaptive icons, notification channels and the
         * foreground-service rules the ear needs are all there, and the
         * tablets Simon ships are newer than that. */
        minSdk = 26
        targetSdk = 34
        /* BUMPED ON EVERY BUILD, and shown on the tablet: a notification
         * that cannot tell you which build it is has cost us a round trip
         * already, because the download page can be a version behind. */
        versionCode = 11
        versionName = "2.0"
    }

    buildTypes {
        release {
            /* Not minified on purpose: an appliance is not an app where
             * size matters, and a stack trace from a customer's tablet is
             * worth more than a smaller APK. */
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
}

dependencies {
    /* Her NAME, heard on the tablet before anything is sent (Keyword.kt).
     * Vosk is offline, Apache-2.0, needs no account and no key, and in
     * grammar mode can only ever return one of the three phrases it is
     * given - which is exactly the property this needs: a television
     * cannot be "transcribed" into a wake word by accident.
     *
     * This is the first dependency this app has ever had, and it is here
     * because there is no way to hear a word on a device without one. */
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    /* Vosk's native side is reached through JNA. */
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
