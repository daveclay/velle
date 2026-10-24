plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // the compiler's tests read examples/billing/billing.velle, whose amounts are `Money`
    // (Reference §5, extension types): the type library it names
    testImplementation(project(":extensions:finance"))
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
