// Alice's library: the `Money` extension type (Reference §5), registered with Velle
// through META-INF/services/velle.ext.VelleType. It knows nothing about storage;
// a store's binding for Money is a library of its own (extensions/finance-sqlite).
plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":compiler")) // the velle.ext API
    testImplementation(kotlin("test"))
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
