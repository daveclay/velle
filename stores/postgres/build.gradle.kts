// The SQL store over PostgreSQL: the SQL layer (stores/sql) with PostgreSQL's
// dialect — its native column types and an identity key column. Test fixtures:
// PostgresTesting, a PostgreSQL in a container (Testcontainers) handing out a
// fresh schema per connection, for this module's conformance run and for the
// examples that re-run their flows over PostgreSQL.
plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":stores:sql"))
    implementation(project(":compiler"))
    implementation("org.postgresql:postgresql:42.7.5")
    testFixturesApi("org.testcontainers:postgresql:1.20.6")
    testFixturesApi("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testFixturesImplementation("org.postgresql:postgresql:42.7.5")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":stores:sql")))
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
