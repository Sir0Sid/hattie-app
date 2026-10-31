/* Hattie — the tablet app.
 *
 * ALMOST ONE MODULE WITH NO LIBRARIES. The app depends on nothing but the
 * Android platform and Kotlin, with ONE exception and it is deliberate:
 * Vosk, for hearing her name on the tablet before anything is sent to the
 * box (see Keyword.kt). A shipped appliance must be buildable for years
 * without a dependency that has moved, so that one is pinned and justified
 * in app/build.gradle.kts rather than added casually.
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Hattie"
include(":app")
