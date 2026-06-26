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
    version = "0.1.150"
    description = "ASTRA QuPath extension for Cellpose-backed tissue analysis"
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
    exclude("**/LauncherPreviewApp*.class")
}

tasks.register<JavaExec>("previewLauncher") {
    group = "ASTRA development"
    description = "Open an ASTRA launcher dialog from working-tree classes."

    dependsOn("testClasses")
    val quPathAppPath = providers.gradleProperty("astraQuPathAppPath")
        .orElse("/Applications/QuPath-0.6.0-x64.app/Contents/app")
    classpath = sourceSets["test"].runtimeClasspath + files(provider {
        quPathAppClasspath(quPathAppPath.get())
    })
    mainClass.set("qupath.ext.astra.LauncherPreviewApp")

    val astraRoot = providers.gradleProperty("astraRoot")
        .orElse(layout.projectDirectory.dir("../astra").asFile.absolutePath)
    val script = providers.gradleProperty("astraPreviewScript").orElse("vascular")
    val snapshots = providers.gradleProperty("astraPreviewSnapshots").orElse("false")
    val snapshotMode = providers.gradleProperty("astraPreviewSnapshotMode").orElse("all")
    val outputDir = providers.gradleProperty("astraPreviewOutput")
        .orElse("/private/tmp/astra-gui-snapshots")
    val userDir = providers.gradleProperty("astraPreviewUserPath")
        .orElse("/private/tmp/astra-qupath-dev-user")
    val javaFxModulePath = providers.gradleProperty("astraJavafxModulePath")
        .map { resolveJavaFxModulePath(it) }
        .orElse(provider { defaultJavaFxModulePath() })
    val javaExecutable = providers.gradleProperty("astraPreviewJavaExecutable")

    args(
        "--astra-root", astraRoot.get(),
        "--script", script.get(),
        "--output", outputDir.get(),
        "--snapshot-mode", snapshotMode.get(),
        "--user-path", userDir.get()
    )
    if (snapshots.get().toBoolean()) {
        args("--snapshots")
    }
    if (javaExecutable.isPresent) {
        setExecutable(javaExecutable.get())
    }

    systemProperty("qupath.prefs.name", "astra-dev-launcher-preview")
    jvmArgs(
        "-Djava.awt.headless=false",
        "--module-path", javaFxModulePath.get(),
        "--add-modules", "javafx.controls,javafx.fxml,javafx.swing,javafx.web",
        "--add-opens", "javafx.graphics/com.sun.javafx.css=ALL-UNNAMED"
    )
}

fun defaultJavaFxModulePath(): String {
    val candidates = listOf(
        file("/Applications/QuPath-0.6.0-x64.app/Contents/app"),
        file("/Applications/QuPath-0.7.0-arm64.app/Contents/app")
    )
    val jars = candidates
        .firstOrNull { dir -> dir.isDirectory && dir.listFiles { file ->
            file.name.startsWith("javafx-") && file.name.endsWith(".jar")
        }?.isNotEmpty() == true }
        ?.listFiles { file ->
            file.name.startsWith("javafx-") && file.name.endsWith(".jar")
        }
        ?.sortedBy { it.name }
        ?: emptyList()
    return jars.joinToString(File.pathSeparator) { it.absolutePath }
}

fun resolveJavaFxModulePath(path: String): String {
    return path.split(File.pathSeparator)
        .flatMap { entry ->
            val file = file(entry)
            if (file.isDirectory) {
                file.listFiles { child ->
                    child.name.startsWith("javafx-") && child.name.endsWith(".jar")
                }?.sortedBy { it.name }.orEmpty()
            } else {
                listOf(file)
            }
        }
        .joinToString(File.pathSeparator) { it.absolutePath }
}

fun quPathAppClasspath(path: String): List<File> {
    val dir = file(path)
    if (!dir.isDirectory) {
        return emptyList()
    }
    return dir.listFiles { child ->
        child.name.endsWith(".jar") && !child.name.startsWith("javafx-")
    }?.sortedBy { it.name }.orEmpty()
}

/*
 * Keep generated API documentation under build/ so repository docs remain handwritten.
 */
tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    setDestinationDir(layout.buildDirectory.dir("docs/javadoc").get().asFile)
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
