// An MCP (Model Context Protocol) server built from the compiled model, served over
// the API layer (apis/json), for an AI chat client: one tool per act with its input
// schema and a description written from the model, the tools read, query, and count
// shared by every shape, the model and the spec's text as resources, and the refusal
// as a result the assistant reads. The MCP Java SDK only: no Spring here; apis/spring
// serves it over HTTP.
plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":apis:json"))
    api("io.modelcontextprotocol.sdk:mcp-core:2.2.0")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.2.0")
    testImplementation(kotlin("test"))
    // the tests run the billing spec, whose amounts are `Money`, and the pharmacy spec
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
