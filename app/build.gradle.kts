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
        versionCode = 5
        versionName = "1.4"
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

/* No dependencies, on purpose. See settings.gradle.kts. */
