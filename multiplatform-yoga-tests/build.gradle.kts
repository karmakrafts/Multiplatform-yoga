plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    mingwX64()
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        nativeMain {
            dependencies {
                implementation(project(":multiplatform-yoga"))
                implementation(libs.kotest.engine)
                implementation(libs.kotlinx.io.bytestring)
                implementation(libs.kotlinx.io.core)
            }
        }
    }
}