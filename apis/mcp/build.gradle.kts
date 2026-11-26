// An MCP (Model Context Protocol) server built from the compiled model, served over
// the API layer (apis/json), for an AI chat client: one tool per act with its input
// schema and a description written from the model, the tools read, query, and count
// shared by every shape, the model and the spec's text as resources, and the refusal
// as a result the assistant reads. The MCP Java SDK only: no Spring here; apis/spring
// serves it over HTTP.
//
// It also holds the authoring server (velle.api.mcp.authoring, OQ174): the velle command's
// validate, compare, describe, and try as tools over a spec's text, and the velle-design skill as
// resources, run over standard input and output from velle-authoring-mcp.jar.
plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":apis:json"))
    api("io.modelcontextprotocol.sdk:mcp-core:2.2.0")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.2.0")
    // the authoring jar carries Velle's own extension type libraries, as the skill's jar does
    runtimeOnly(project(":extensions:finance"))
    testImplementation(kotlin("test"))
    // the tests run the billing spec, whose amounts are `Money`, and the pharmacy spec
    testImplementation(project(":extensions:finance"))
}

tasks.test {
    useJUnitPlatform()
    // AuthoringJarTest starts the built jar as a process
    dependsOn("authoringJar")
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

// ── The authoring server's jar (OQ174) ───────────────────────────────────────

val skillFolder = rootProject.layout.projectDirectory.dir("skills/velle-design")

// The skill's SKILL.md and references/, packed into the jar under velle-design/ with files.txt
// listing them, so the server reads them as resources and the source is never copied.
val skillResources = tasks.register("skillResources") {
    description = "Pack skills/velle-design/SKILL.md and references/ as resources, with a listing of them"
    val skillFiles = fileTree(skillFolder) { include("SKILL.md", "references/**") }
    val packedDirectory = layout.buildDirectory.dir("skill-resources")
    inputs.files(skillFiles)
    outputs.dir(packedDirectory)
    doLast {
        val folder = packedDirectory.get().dir("velle-design").asFile
        folder.deleteRecursively()
        val skillRoot = skillFolder.asFile
        val paths = skillFiles.files.map { file -> file.relativeTo(skillRoot).invariantSeparatorsPath }.sorted()
        paths.forEach { path -> File(skillRoot, path).copyTo(File(folder, path)) }
        File(folder, "files.txt").writeText(paths.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main { resources.srcDir(skillResources) }

/** Everything the authoring jar is made of: this module, the compiler, the API layer, the finance types, the MCP SDK and its JSON binding, and the Kotlin standard library. */
val authoringJarContents: FileCollection = files(sourceSets.main.map { mainSourceSet -> mainSourceSet.output }) + configurations.runtimeClasspath.get()

apply(from = rootProject.file("gradle/merged-services.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val writeMergedServices = extra["writeMergedServices"] as (Set<File>, File) -> Unit

val authoringServices = tasks.register("authoringServices") {
    description = "Merge the META-INF/services files of everything the authoring jar holds, so ServiceLoader finds every type and the JSON binding"
    val contents = authoringJarContents
    val servicesDirectory = layout.buildDirectory.dir("authoring-services")
    inputs.files(contents)
    outputs.dir(servicesDirectory)
    doLast { writeMergedServices(contents.files, servicesDirectory.get().asFile) }
}

tasks.register<Jar>("authoringJar") {
    description = "Build apis/mcp/build/libs/velle-authoring-mcp.jar: the authoring MCP server, run over standard input and output"
    dependsOn(authoringServices)
    archiveFileName.set("velle-authoring-mcp.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "velle.api.mcp.authoring.AuthoringMcpKt") }
    from(authoringServices)
    from(sourceSets.main.map { mainSourceSet -> mainSourceSet.output }) { exclude("META-INF/services/**") }
    from(authoringJarContents.filter { file -> file.isFile }.elements.map { jars -> jars.map { jar -> zipTree(jar) } }) {
        exclude("META-INF/services/**", "META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
    }
}
