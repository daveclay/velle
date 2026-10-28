// The Spring Boot starter: a system declared as a bean is served over REST and
// GraphQL, connected to the application's DataSource through the SQL store,
// ticked on the schedules the application configures, and shown in a console
// page. Every operation goes through the API layer (apis/json); this module
// only maps HTTP onto it. The application brings the generated system, the
// database driver, and the actor resolver.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    api(project(":apis:graphql"))
    api(project(":stores:postgres"))
    api(project(":stores:sqlite"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    // Spring instantiates a Kotlin class named in spring.factories through its Kotlin support, which needs kotlin-reflect
    implementation(kotlin("reflect"))
    // the DataSource is the application's: it brings spring-boot-starter-jdbc and its driver;
    // without one the system runs in memory. The compile needs Boot's DataSource auto-configuration class.
    compileOnly("org.springframework.boot:spring-boot-jdbc")
    // metrics are the application's choice: with a MeterRegistry present the ticks are counted
    compileOnly("io.micrometer:micrometer-core")
    testImplementation("io.micrometer:micrometer-core")
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(project(":extensions:finance"))
    testRuntimeOnly(project(":extensions:finance-sql"))
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
