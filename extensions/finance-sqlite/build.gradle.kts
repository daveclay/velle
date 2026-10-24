// Bob's library: how the generic SQLite store holds Alice's `Money` (Reference §5,
// "Extension types"; store bindings). Registered through
// META-INF/services/velle.sqlite.SqliteTypeBinding; a system that stores Money over
// SqliteStore puts this on its classpath and the store finds it.
plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":compiler"))
    implementation(project(":stores:sqlite"))
    implementation(project(":extensions:finance"))
    testImplementation(kotlin("test"))
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
}

tasks.test {
    useJUnitPlatform()
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
