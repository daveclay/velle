import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
}

group = "velle"

repositories {
    mavenCentral()
}

// The libraries the skill's jar carries beside the compiler (OQ174): Velle's own extension type
// libraries, and no store binding. `:extensions:finance` depends on this project, so it is reached
// through a configuration of its own that nothing here compiles against, never a normal dependency.
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
}

tasks.test {
    useJUnitPlatform()
    // SkillJarTest runs skills/velle-design/scripts/velle, which runs the jar
    dependsOn("skillJar")
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

/** A service file names one implementation per line; every jar's lines for one service go into one file. */
fun mergeServiceLines(serviceLines: MutableMap<String, MutableSet<String>>, serviceName: String, text: String) {
    val implementations = text.lines().map { line -> line.substringBefore('#').trim() }.filter { line -> line.isNotEmpty() }
    serviceLines.getOrPut(serviceName) { linkedSetOf() }.addAll(implementations)
}

fun readServicesFromDirectory(directory: File, serviceLines: MutableMap<String, MutableSet<String>>) {
    val servicesDirectory = File(directory, "META-INF/services")
    servicesDirectory.listFiles()?.filter { file -> file.isFile }?.forEach { serviceFile ->
        mergeServiceLines(serviceLines, serviceFile.name, serviceFile.readText())
    }
}

fun readServicesFromJar(jar: File, serviceLines: MutableMap<String, MutableSet<String>>) {
    ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { entry -> !entry.isDirectory && entry.name.startsWith("META-INF/services/") }
            .forEach { entry ->
                val text = zip.getInputStream(entry).bufferedReader().readText()
                mergeServiceLines(serviceLines, entry.name.removePrefix("META-INF/services/"), text)
            }
    }
}

val skillServices = tasks.register("skillServices") {
    description = "Merge the META-INF/services files of everything the skill's jar holds, so ServiceLoader finds every type"
    val contents = skillJarContents
    val servicesDirectory = layout.buildDirectory.dir("skill-services")
    inputs.files(contents)
    outputs.dir(servicesDirectory)
    doLast {
        val serviceLines = sortedMapOf<String, MutableSet<String>>()
        contents.files.filter { file -> file.isDirectory }.forEach { directory -> readServicesFromDirectory(directory, serviceLines) }
        contents.files.filter { file -> file.isFile && file.name.endsWith(".jar") }.forEach { jar -> readServicesFromJar(jar, serviceLines) }
        val outputDirectory = servicesDirectory.get().dir("META-INF/services").asFile
        outputDirectory.deleteRecursively()
        outputDirectory.mkdirs()
        serviceLines.forEach { (serviceName, implementations) ->
            File(outputDirectory, serviceName).writeText(implementations.joinToString("\n", postfix = "\n"))
        }
    }
}

// Run by hand after the wiki page changes, never by the tests: SkillJarTest fails while the copy
// in the skill differs from the wiki page, and says to run this.
val copyBestPractices = tasks.register<Copy>("copyBestPractices") {
    description = "Copy wiki/Guides/Best-practices.md into the skill, so the skill carries the best practices of its compiler"
    from(rootProject.layout.projectDirectory.file("wiki/Guides/Best-practices.md"))
    into(skillFolder.dir("references"))
}

tasks.register<Jar>("skillJar") {
    description = "Build skills/velle-design/scripts/velle.jar: the compiler, the Kotlin standard library, and extensions/finance"
    dependsOn(skillServices)
    destinationDirectory.set(skillFolder.dir("scripts"))
    archiveFileName.set("velle.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "velle.CliKt") }
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
