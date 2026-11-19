package velle.api.spring

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The console: one page, served at `velle.console.path` (`/velle`), that reads the
 * model's description from the REST API and everything else over GraphQL, and builds
 * itself from the description. Every readable shape as a table with the query's `where`
 * and order, every record as a page of its members with its references as links, every
 * act as a form, every schedule as a page that says when it last ran, with a button
 * that ticks it when ticks are exposed. Nothing in it is written per spec.
 *
 * The page is built by the `console/` project into one file, which this module's build
 * takes as the resource `velle/console.html`. It carries three placeholders this class
 * fills: where the REST API is, where GraphQL is, and whether ticks are exposed.
 */
@RestController
@ConditionalOnProperty(prefix = "velle.console", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class VelleConsole(private val props: VelleProperties, private val environment: Environment) {

    private val page: String by lazy {
        val builtPage = VelleConsole::class.java.getResourceAsStream("/velle/console.html")!!.bufferedReader().readText()
        builtPage
            .replace("__API__", props.api.path)
            .replace("__GRAPHQL__", graphQlPath())
            .replace("__TICKS__", props.api.ticks.toString())
    }

    /** Where Spring serves GraphQL: the application's own setting, or Spring's default. */
    private fun graphQlPath(): String = environment.getProperty("spring.graphql.http.path", "/graphql")

    @GetMapping("\${velle.console.path:/velle}", produces = [MediaType.TEXT_HTML_VALUE])
    fun console(): ResponseEntity<String> = ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page)
}
