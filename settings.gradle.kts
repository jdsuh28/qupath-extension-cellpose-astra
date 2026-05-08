pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }

    /*
     * Resolve the QuPath settings plugin directly to its implementation module.
     *
     * In clean CI runners, Gradle may fail while looking up the plugin-marker
     * artifact for io.github.qupath.qupath-extension-settings. The implementation
     * artifact itself is published as io.github.qupath:qupath-gradle-plugin, so
     * this mapping keeps resolution deterministic without relying on a local
     * Gradle cache.
     */
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.qupath.qupath-extension-settings") {
                useModule("io.github.qupath:qupath-gradle-plugin:${requested.version}")
            }
        }
    }
}

qupath {
    version = "0.6.0"
}

// Apply QuPath Gradle settings plugin to handle configuration
plugins {
    id("io.github.qupath.qupath-extension-settings") version "0.2.1"
}
