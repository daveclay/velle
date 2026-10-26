// The SQL layer: a generic, model-driven store over any SQL database — the
// framework role in Reference §22's architecture. It speaks the runtime's
// StateResolver / CommitCallback protocol (compiler/.../Hydration.kt) for any
// spec, containing no business names and no database's name. What one database
// does differently is a SqlDialect (stores/sqlite, stores/postgres); how a store
// holds an extension type is a SqlTypeBinding (extensions/finance-sql). No JDBC
// driver lives here: a dialect's module brings its own.
//
// Test fixtures: SqlStoreConformance, the scenarios every dialect must pass,
// run by each dialect module's own test over its database.
plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":compiler"))
    testFixturesApi(project(":compiler"))
    testFixturesApi(kotlin("test-junit5"))
    testFixturesImplementation(project(":extensions:finance"))
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
