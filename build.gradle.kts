plugins {
    kotlin("jvm") version "2.2.20" apply false
    // the Spring Boot starter (apis/spring) and the example applications (examples/<system>/app)
    kotlin("plugin.spring") version "2.2.20" apply false
    id("org.springframework.boot") version "4.1.2" apply false
}

// Every Kotlin module writes a JaCoCo XML report, build/reports/jacoco/test/jacocoTestReport.xml,
// when `gradlew test jacocoTestReport` runs. The ub-mode tools crapper and mutator read it
// (`.claude/skills/ub-mode/SKILL.md`).
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "jacoco")
        tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
            reports.xml.required.set(true)
        }
    }
}

tasks.register("generate") {
    description = "Regenerate every example's transpiled output from its .velle spec"
    dependsOn(
        ":examples:billing:output:generate",
        ":examples:membership:output:generate",
        ":examples:payments:output:generate",
        ":examples:partition-drift:output:generate",
        ":examples:gallery:output:generate",
        ":examples:enrollment:output:generate",
        ":examples:loyalty:output:generate",
        ":examples:orders:output:generate",
        ":examples:moderation:output:generate",
        ":examples:peptides:output:generate",
        ":examples:shipping:output:generate",
        ":examples:pharmacy:output:generate",
        ":examples:batches:output:generate",
        ":examples:platelayout:output:generate",
        ":examples:bookings:output:generate",
    )
}
