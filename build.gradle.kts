plugins {
    kotlin("jvm") version "2.2.20" apply false
    // the Spring Boot starter (apis/spring) and the example applications (examples/<system>/app)
    kotlin("plugin.spring") version "2.2.20" apply false
    id("org.springframework.boot") version "4.1.2" apply false
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
