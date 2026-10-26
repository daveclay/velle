// A GraphQL schema built from the compiled model, served over the API layer
// (apis/json): one object type per readable shape and refinement, a union per
// static union, one query root and one page root per shape, one mutation per act
// with its input type, and the refusal as data. graphql-java only: no Spring
// here, so any server that hosts a graphql-java schema can host this one.
plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":apis:json"))
    api("com.graphql-java:graphql-java:25.1")
    testImplementation(kotlin("test"))
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
