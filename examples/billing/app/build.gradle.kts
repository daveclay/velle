// The billing system as a Spring Boot application over PostgreSQL: the end state
// Reference §22's architecture points at. The generated system (examples/billing/output)
// is declared as one bean; the starter (apis/spring) connects it to the database,
// serves every act and shape over REST and GraphQL, ticks the schedules, and
// serves the console. The hand-written code here is the application's own:
// the bean, the configuration, and one typed endpoint over the generated surface.
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
    implementation(project(":examples:billing:output"))
    implementation(project(":extensions:finance"))
    runtimeOnly(project(":extensions:finance-sql"))
    runtimeOnly("org.postgresql:postgresql")
    // `gradle bootRun` starts the PostgreSQL in compose.yaml and points the DataSource at it
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
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
