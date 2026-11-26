plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

// The libraries the skill's jar carries beside the compiler (OQ174): Velle's own extension type
// libraries, the API layer that `velle try` runs a system through, and no store binding.
// `:extensions:finance` and `:apis:json` depend on this project, so they are reached through a
// configuration of their own that nothing here compiles against, never a normal dependency.
val skillLibraries: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    testImplementation(kotlin("test"))
    // the compiler's tests read examples/billing/billing.velle, whose amounts are `Money`
    // (Reference §5, extension types): the type library it names
    testImplementation(project(":extensions:finance"))
    skillLibraries(project(":extensions:finance"))
    skillLibraries(project(":apis:json"))
}

tasks.test {
    useJUnitPlatform()
    // SkillJarTest runs skills/velle-design/scripts/velle, which runs the jar
    dependsOn("skillJar")
    // SkillReferenceTest checks the skill's language reference, so an edit to it reruns the tests
    inputs.dir(rootProject.layout.projectDirectory.dir("skills/velle-design/references"))
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

// ── The skill's jar (OQ174) ──────────────────────────────────────────────────

val skillFolder = rootProject.layout.projectDirectory.dir("skills/velle-design")

/** Everything the skill's jar is made of: the compiler, the Kotlin standard library, and the skill's libraries. */
val skillJarContents: FileCollection = files(sourceSets.main.map { mainSourceSet -> mainSourceSet.output }) +
    configurations.runtimeClasspath.get() + skillLibraries

apply(from = rootProject.file("gradle/merged-services.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val writeMergedServices = extra["writeMergedServices"] as (Set<File>, File) -> Unit

val skillServices = tasks.register("skillServices") {
    description = "Merge the META-INF/services files of everything the skill's jar holds, so ServiceLoader finds every type"
    val contents = skillJarContents
    val servicesDirectory = layout.buildDirectory.dir("skill-services")
    inputs.files(contents)
    outputs.dir(servicesDirectory)
    doLast { writeMergedServices(contents.files, servicesDirectory.get().asFile) }
}

// Run by hand after the wiki page changes, never by the tests: SkillJarTest fails while the copy
// in the skill differs from the wiki page, and says to run this.
val copyBestPractices = tasks.register<Copy>("copyBestPractices") {
    description = "Copy wiki/Guides/Best-practices.md into the skill, so the skill carries the best practices of its compiler"
    from(rootProject.layout.projectDirectory.file("wiki/Guides/Best-practices.md"))
    into(skillFolder.dir("references"))
}

tasks.register<Jar>("skillJar") {
    description = "Build skills/velle-design/scripts/velle.jar: the compiler, the Kotlin standard library, extensions/finance, and apis/json"
    dependsOn(skillServices)
    destinationDirectory.set(skillFolder.dir("scripts"))
    archiveFileName.set("velle.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // the whole command, `try` included, starts in the API layer (apis/json, velle.api.VelleCommand)
    manifest { attributes("Main-Class" to "velle.api.VelleCommandKt") }
    from(skillServices)
    from(sourceSets.main.map { mainSourceSet -> mainSourceSet.output }) { exclude("META-INF/services/**") }
    from(skillJarContents.filter { file -> file.isFile }.elements.map { jars -> jars.map { jar -> zipTree(jar) } }) {
        exclude("META-INF/services/**", "META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
    }
}

tasks.register("skill") {
    description = "Build the whole skill folder: the jar, and the best practices page copied from the wiki"
    dependsOn("skillJar", copyBestPractices)
}
