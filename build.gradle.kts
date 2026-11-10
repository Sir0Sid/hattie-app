/* Hattie — the tablet app. See Hattie-Room-Voice-Plan.md, "The app". */
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
defaultConfig {
    applicationId = "pro.hattie.app"
    versionCode = 9          // a whole number that must go UP every build
    versionName = "1.8"      // the text you see in the notification
}
