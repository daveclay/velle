// The SQL store over SQLite: the SQL layer (stores/sql) with SQLite's dialect —
// its columns, its key column, its REGEXP function. Each example's output module
// uses it to run its scenarios over real storage; an engineer's own store would
// take its place.
plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":stores:sql"))
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
