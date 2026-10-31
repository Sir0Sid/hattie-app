/* Hattie — the tablet app.
 *
 * ONE MODULE, NO LIBRARIES. The app deliberately depends on nothing but
 * the Android platform and Kotlin: a shipped appliance must be buildable
 * for years without a dependency that has moved, and every library is one
 * more thing that can break a build a customer is waiting on.
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
