plugins {
    kotlin("jvm") version "1.6.20"
    id("org.jetbrains.dokka") version ("1.6.20") apply false
}

// The versioning plugin should be applied in all submodules
subprojects {
    repositories {
        mavenCentral()
    }
    apply {
        plugin("org.jetbrains.kotlin.jvm")
        plugin("org.jetbrains.dokka")
    }
    val dokkaPlugin by configurations
    dependencies {
        dokkaPlugin("org.jetbrains.dokka:versioning-plugin:1.6.20")
    }
}
