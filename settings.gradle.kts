pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
        maven {
            url = uri("https://maven.scijava.org/content/repositories/ome-releases")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
        maven {
            url = uri("https://maven.scijava.org/content/repositories/ome-releases")
        }
    }
}

rootProject.name = "qupath-extension-cellpose-astra"
