pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "SciJavaPublic"
            url = uri("https://maven.scijava.org/content/groups/public")
        }
        maven {
            name = "SciJavaReleases"
            url = uri("https://maven.scijava.org/content/repositories/releases")
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
