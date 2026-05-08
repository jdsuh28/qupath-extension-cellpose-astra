plugins {
    `java-library`
    `maven-publish`
    id("org.openjfx.javafxplugin") version "0.1.0"
}

/*
 * ASTRA-specific extension toolchain.
 */
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

/*
 * ASTRA-specific extension metadata.
 */
group = "io.github.jdsuh28"
version = "0.1.43"
description = "ASTRA fork of the BIOP Cellpose extension for QuPath"

val extensionName = "qupath-extension-cellpose-astra"
val automaticModuleName = "qupath.ext.astra.cellpose"

base {
    archivesName.set(extensionName)
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls", "javafx.swing")
}

dependencies {
    implementation("io.github.qupath:qupath-gui-fx:0.6.0")
    implementation("io.github.qupath:qupath-fxtras:0.2.0")
    implementation("io.github.qupath:extensionmanager:1.0.0")
    implementation("commons-io:commons-io:2.15.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<ProcessResources>().configureEach {
    from("${projectDir}/LICENSE") {
        into("META-INF/licenses/")
    }
}

tasks.register<Sync>("copyResources") {
    description = "Copy dependencies into the build directory for use elsewhere"
    group = "QuPath"
    from(configurations.default)
    into("build/libs")
}

/*
 * Set HTML language and destination folder.
 */
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    setDestinationDir(File(project.rootDir, "docs"))
}

/*
 * Avoid duplicate resource failures when building source or runtime jars.
 */
tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    manifest {
        attributes(
            "Implementation-Title" to extensionName,
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to automaticModuleName
        )
    }
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
