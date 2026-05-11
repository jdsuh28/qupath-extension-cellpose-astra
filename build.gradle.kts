plugins {
    id("org.bytedeco.gradle-javacpp-platform") version "1.5.10" apply false
    id("org.openjfx.javafxplugin") version "0.1.0" apply false
    id("maven-publish")
    // QuPath Gradle extension convention plugin
    id("qupath-conventions") version "0.2.1"
}

/*
 * ASTRA-specific extension toolchain.
 */
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    maven {
        url = uri("gradle/local-maven")
    }
}

/*
 * ASTRA-specific extension metadata.
 */
qupathExtension {
    name = "qupath-extension-cellpose-astra"
    group = "io.github.jdsuh28"
    version = "0.1.45"
    description = "ASTRA fork of the BIOP Cellpose extension for QuPath"
    automaticModule = "qupath.ext.astra.cellpose"
}

dependencies {
    implementation(files(
        "gradle/local-maven/io/github/qupath/qupath-gui-fx/0.6.0/qupath-gui-fx-0.6.0.jar",
        "gradle/local-maven/io/github/qupath/qupath-core/0.6.0/qupath-core-0.6.0.jar",
        "gradle/local-maven/io/github/qupath/qupath-core-processing/0.6.0/qupath-core-processing-0.6.0.jar",
        "gradle/local-maven/io/github/qupath/qupath-bioimageio-spec/0.2.0/qupath-bioimageio-spec-0.2.0.jar",
        "gradle/local-maven/io/github/qupath/qupath-fxtras/0.2.0/qupath-fxtras-0.2.0.jar",
        "gradle/local-maven/io/github/qupath/extensionmanager/1.0.0/extensionmanager-1.0.0.jar"
    ))
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    implementation("org.bytedeco:opencv-platform:4.10.0-1.5.11")
    implementation("net.imagej:ij:1.54k")
    implementation("org.controlsfx:controlsfx:11.2.2")
    implementation("org.slf4j:slf4j-api:2.0.0")
    implementation("commons-io:commons-io:2.15.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

/*
 * Set HTML language and destination folder
 */
tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    setDestinationDir(File(project.rootDir, "docs"))
}

/*
 * Avoid "Entry .gitkeep is a duplicate but no duplicate handling strategy has been set."
 * when using withSourcesJar()
 */
tasks.withType<org.gradle.jvm.tasks.Jar> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

publishing {
    repositories {
        maven {
            name = "SciJava"
            val releasesRepoUrl = "https://maven.scijava.org/content/repositories/releases"
            val snapshotsRepoUrl = "https://maven.scijava.org/content/repositories/snapshots"
            url = if (project.hasProperty("release")) uri(releasesRepoUrl) else uri(snapshotsRepoUrl)
            credentials {
                username = System.getenv("MAVEN_USER")
                password = System.getenv("MAVEN_PASS")
            }
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                licenses {
                    license {
                        name = "Apache License v2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
            }
        }
    }
}
