import com.github.gradle.node.npm.task.NpmTask

// The console: a React and TypeScript project built by Vite into one file, dist/index.html,
// which the Spring Boot starter (:apis:spring) takes as its console page. Gradle downloads the
// Node it runs, so building Velle asks for nothing but a JDK.
plugins {
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    download.set(true)
    version.set("24.11.0")
    // the versions package-lock.json names, never newer ones
    npmInstallCommand.set("ci")
}

val consoleSources = files("index.html", "package.json", "package-lock.json", "tsconfig.json", "vite.config.ts")

tasks.register<NpmTask>("buildConsole") {
    description = "Type-check the console and build it into dist/index.html"
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
    inputs.dir("src")
    inputs.files(consoleSources)
    outputs.dir("dist")
}

tasks.register<NpmTask>("test") {
    description = "Run the console's tests"
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("test"))
    inputs.dir("src")
    inputs.files(consoleSources)
    outputs.upToDateWhen { true }
}
