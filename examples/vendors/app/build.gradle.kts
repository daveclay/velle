// The vendors system (a catalogue of products from vendors, kept by assigned staff) as a Spring Boot application over PostgreSQL,
// on the pattern of examples/billing/app: the generated system
// (examples/vendors/output) is declared as one bean, and the starter (apis/spring)
// connects it to the database, serves every act and shape over REST and GraphQL,
// ticks the schedules, and serves the console.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation(project(":apis:spring"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(project(":examples:vendors:output"))
    runtimeOnly("org.postgresql:postgresql")
    // `gradle bootRun` starts the PostgreSQL in compose.yaml and points the DataSource at it
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // Spring reads a controller's parameter names from the class file
        javaParameters.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
