plugins {
    kotlin("jvm") version "2.2.20" apply false
}

tasks.register("generate") {
    description = "Regenerate every example's transpiled output from its .velle spec"
    dependsOn(":examples:billing:output:generate", ":examples:membership:output:generate")
}
