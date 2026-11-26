// The API layer: a running system's operations in wire terms — commit an act,
// read a record, run a query, fire a tick, describe the model — over JSON-shaped
// values (maps, lists, strings, numbers, booleans) and nothing else. Driven by
// the compiled model, so it serves any spec; names no transport and no
// framework. A transport (apis/spring's REST controller, apis/graphql's schema)
// maps requests onto these operations and their answers back.
plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":compiler"))
    // `velle try` reads its scenario with Jackson's streaming reader alone, the JSON library the
    // repo already uses (apis/mcp), without the object mapper, so the skill's jar stays small
    implementation("tools.jackson.core:jackson-core:3.2.1")
    testImplementation(kotlin("test"))
    // the tests run the billing spec, whose amounts are `Money`, in memory and over SQLite
    testImplementation(project(":extensions:finance"))
    testImplementation(project(":stores:sqlite"))
    testRuntimeOnly(project(":extensions:finance-sql"))
}

tasks.test {
    useJUnitPlatform()
    // ScenarioReferenceTest runs every scenario in the skill's reference
    inputs.dir(rootProject.layout.projectDirectory.dir("skills/velle-design/references"))
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
