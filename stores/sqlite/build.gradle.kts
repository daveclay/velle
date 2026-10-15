// A generic, model-driven store backend over SQLite — the framework role in
// Reference §22's architecture: it speaks the runtime's StateResolver /
// CommitCallback protocol (compiler/.../Hydration.kt) for any spec, containing
// no business names. Each example's output module uses it to run its scenarios
// over real storage; an engineer's own store would take its place.
plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":compiler"))
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
