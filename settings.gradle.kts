pluginManagement {
    repositories {
        maven {
            url = uri("gradle/local-maven")
        }
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("gradle/local-maven")
        }
        mavenCentral()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
        maven {
            url = uri("https://maven.scijava.org/content/repositories/snapshots")
        }
    }

    versionCatalogs {
        create("libs") {
            library("qupath-gui-fx", "io.github.qupath", "qupath-gui-fx").version("0.6.0")
            library("qupath-fxtras", "io.github.qupath", "qupath-fxtras").version("0.2.0")
            library("extensionmanager", "io.github.qupath", "extensionmanager").version("1.0.0")
        }
    }
}

rootProject.name = "qupath-extension-cellpose-astra"
