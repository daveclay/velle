// The merge of META-INF/services files a self-contained jar needs: every jar's lines for one service
// go into one file, so ServiceLoader finds every implementation, such as each extension type. Applied
// by each project that builds such a jar (compiler's skill jar, apis/mcp's authoring server jar), which
// call it as `extra["writeMergedServices"]`.
import java.util.zip.ZipFile

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

/** Writes into [outputDirectory]/META-INF/services one file per service, merged from every directory and jar in [contents]. */
fun writeMergedServices(contents: Set<File>, outputDirectory: File) {
    val serviceLines = sortedMapOf<String, MutableSet<String>>()
    contents.filter { file -> file.isDirectory }.forEach { directory -> readServicesFromDirectory(directory, serviceLines) }
    contents.filter { file -> file.isFile && file.name.endsWith(".jar") }.forEach { jar -> readServicesFromJar(jar, serviceLines) }
    val servicesDirectory = File(outputDirectory, "META-INF/services")
    servicesDirectory.deleteRecursively()
    servicesDirectory.mkdirs()
    serviceLines.forEach { (serviceName, implementations) ->
        File(servicesDirectory, serviceName).writeText(implementations.joinToString("\n", postfix = "\n"))
    }
}

extra["writeMergedServices"] = { contents: Set<File>, outputDirectory: File -> writeMergedServices(contents, outputDirectory) }
