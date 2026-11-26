package velle.api.mcp.authoring

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult
import io.modelcontextprotocol.spec.McpSchema.Prompt
import io.modelcontextprotocol.spec.McpSchema.PromptMessage
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult
import io.modelcontextprotocol.spec.McpSchema.Resource
import io.modelcontextprotocol.spec.McpSchema.Role
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents

/**
 * The velle-design skill as resources: `SKILL.md` and every file in its `references/`, at
 * `velle-design://SKILL.md` and `velle-design://references/<file>`, and the prompt `velle-design`
 * that hands the assistant `SKILL.md`. The build packs the files from `skills/velle-design/` into the
 * jar under `velle-design/`, with `velle-design/files.txt` listing them, so the source is never copied.
 */
internal object SkillFiles {

    private const val FOLDER = "velle-design"
    private const val SKILL = "SKILL.md"
    const val SCHEME = "velle-design://"

    /** The skill's files by their path under the skill's folder, `SKILL.md` first. */
    val paths: List<String> by lazy {
        val listing = packed("files.txt") ?: error("the jar holds no $FOLDER/files.txt; build it with Gradle, which packs the skill")
        listing.lines().filter { line -> line.isNotBlank() }.sortedBy { path -> if (path == SKILL) "" else path }
    }

    val resources: List<SyncResourceSpecification> get() = paths.map { path -> resource(path) }

    private fun resource(path: String): SyncResourceSpecification {
        val uri = SCHEME + path
        val mimeType = if (path.endsWith(".md")) "text/markdown" else "text/plain"
        val described = if (path == SKILL) "How to run a design session in Velle: the skill an assistant follows." else "The skill's reference ${path.removePrefix("references/")}."
        val resource = Resource.builder(uri, path).title(path).description(described).mimeType(mimeType).build()
        return SyncResourceSpecification(resource) { _, _ -> ReadResourceResult(listOf(TextResourceContents(uri, mimeType, text(path)))) }
    }

    val prompt: SyncPromptSpecification
        get() {
            val prompt = Prompt("velle-design", "Velle design session", "Run a design session that writes a system's business rules in Velle, following the velle-design skill.", emptyList())
            return SyncPromptSpecification(prompt) { _, _ ->
                GetPromptResult("The velle-design skill", listOf(PromptMessage.builder(Role.USER, TextContent(text(SKILL))).build()))
            }
        }

    fun text(path: String): String = packed(path) ?: error("the jar holds no $FOLDER/$path")

    private fun packed(path: String): String? =
        SkillFiles::class.java.getResourceAsStream("/$FOLDER/$path")?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
}
