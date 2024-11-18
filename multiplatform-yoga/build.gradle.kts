/*
 * Copyright 2024 Karma Krafts & associates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import de.undercouch.gradle.tasks.download.Download
import org.gradle.internal.extensions.stdlib.capitalized
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.notExists

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.downloadTask)
    `maven-publish`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

operator fun DirectoryProperty.div(name: String): Path = get().asFile.toPath() / name

fun downloadYogaBinariesTask(platform: String, arch: String): Download =
    tasks.create<Download>("downloadYogaBinaries${platform.capitalized()}${arch.capitalized()}") {
        group = "yogaBinaries"
        val fileName = "build-$platform-$arch-debug.zip"
        src("https://git.karmakrafts.dev/api/v4/projects/339/packages/generic/build/${libs.versions.yoga.get()}/$fileName")
        val destPath = layout.buildDirectory / "yoga" / fileName
        dest(destPath.toFile())
        overwrite(true) // Always overwrite when downloading binaries
        onlyIf { destPath.notExists() }
    }

val downloadSdlBinariesWindowsX64: Download = downloadYogaBinariesTask("windows", "x64")
val downloadSdlBinariesLinuxX64: Download = downloadYogaBinariesTask("linux", "x64")
val downloadSdlBinariesLinuxArm64: Download = downloadYogaBinariesTask("linux", "arm64")
val downloadSdlBinariesMacosX64: Download = downloadYogaBinariesTask("macos", "x64")
val downloadSdlBinariesMacosArm64: Download = downloadYogaBinariesTask("macos", "arm64")

fun extractYogaBinariesTask(platform: String, arch: String): Copy =
    tasks.create<Copy>("extractYogaBinaries${platform.capitalized()}${arch.capitalized()}") {
        group = "yogaBinaries"
        val downloadTaskName = "downloadYogaBinaries${platform.capitalized()}${arch.capitalized()}"
        dependsOn(downloadTaskName)
        val platformPair = "$platform-$arch"
        from(zipTree((layout.buildDirectory / "yoga" / "build-$platformPair-debug.zip").toFile()))
        val destPath = layout.buildDirectory / "yoga" / platformPair
        into(destPath.toFile())
        onlyIf { destPath.notExists() }
    }

val extractSdlBinariesWindowsX64: Copy = extractYogaBinariesTask("windows", "x64")
val extractSdlBinariesLinuxX64: Copy = extractYogaBinariesTask("linux", "x64")
val extractSdlBinariesLinuxArm64: Copy = extractYogaBinariesTask("linux", "arm64")
val extractSdlBinariesMacosX64: Copy = extractYogaBinariesTask("macos", "x64")
val extractSdlBinariesMacosArm64: Copy = extractYogaBinariesTask("macos", "arm64")

val extractYogaBinaries: Task = tasks.create("extractYogaBinaries") {
    group = "yogaBinaries"
    dependsOn(extractSdlBinariesWindowsX64)
    dependsOn(extractSdlBinariesLinuxX64)
    dependsOn(extractSdlBinariesLinuxArm64)
    dependsOn(extractSdlBinariesMacosX64)
    dependsOn(extractSdlBinariesMacosArm64)
}

val downloadYogaHeaders: Exec = tasks.create<Exec>("downloadYogaHeaders") {
    group = "yogaHeaders"
    workingDir = layout.buildDirectory.get().asFile
    commandLine("git", "clone", "--branch", libs.versions.yoga.get(), "--single-branch", "https://github.com/facebook/yoga", "yoga/headers")
    onlyIf { (layout.buildDirectory / "yoga" / "headers").notExists() }
}

val updateYogaHeaders: Exec = tasks.create<Exec>("updateYogaHeaders") {
    group = "yogaHeaders"
    dependsOn(downloadYogaHeaders)
    workingDir = (layout.buildDirectory / "yoga" / "headers").toFile()
    commandLine("git", "pull", "--force")
    onlyIf { (layout.buildDirectory / "yoga" / "headers").exists() }
}

kotlin {
    listOf(
        mingwX64(), linuxX64(), linuxArm64(), macosX64(), macosArm64()
    ).forEach {
        it.compilations.getByName("main") {
            cinterops {
                val yoga by creating {
                    tasks.getByName(interopProcessingTaskName) {
                        dependsOn(updateYogaHeaders)
                        dependsOn(extractYogaBinaries)
                    }
                }
            }
        }
    }
    applyDefaultHierarchyTemplate()
}

val dokkaJar by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaHtml)
    from(tasks.dokkaHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

tasks {
    dokkaHtml {
        dokkaSourceSets.create("main") {
            reportUndocumented = false
            jdkVersion = java.toolchain.languageVersion.get().asInt()
            noAndroidSdkLink = true
            externalDocumentationLink("https://docs.karmakrafts.dev/multiplatform-sdl")
        }
    }
    System.getProperty("publishDocs.root")?.let { docsDir ->
        create<Copy>("publishDocs") {
            mustRunAfter(dokkaJar)
            from(zipTree(dokkaJar.get().outputs.files.first()))
            into(docsDir)
        }
    }
}

publishing {
    System.getenv("CI_API_V4_URL")?.let { apiUrl ->
        repositories {
            maven {
                url = uri("$apiUrl/projects/${System.getenv("CI_PROJECT_ID")}/packages/maven")
                name = "GitLab"
                credentials(HttpHeaderCredentials::class) {
                    name = "Job-Token"
                    value = System.getenv("CI_JOB_TOKEN")
                }
                authentication {
                    create("header", HttpHeaderAuthentication::class)
                }
            }
        }
    }
    publications.configureEach {
        if (this is MavenPublication) {
            artifact(dokkaJar)
            pom {
                name = project.name
                description = "Multiplatform bindings for SDL3 on Linux, Windows and macOS."
                url = System.getenv("CI_PROJECT_URL")
                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "kitsunealex"
                        name = "KitsuneAlex"
                        url = "https://git.karmakrafts.dev/KitsuneAlex"
                    }
                }
                scm {
                    url = this@pom.url
                }
            }
        }
    }
}