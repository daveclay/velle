plugins {
    kotlin("jvm") version "2.2.20"
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
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

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generate") {
    description = "Regenerate the MockHarness typed surface from billing.velle"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("velle.GenerateKt")
    args = listOf("billing.velle", "Billing")
}
