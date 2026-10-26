package velle.api.spring

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The console: one page, served at `velle.console.path` (`/velle`), that reads the
 * model from the REST API and builds itself from it. Every readable shape as a
 * table with the query's `where` and order, every record as a page of its
 * members with its references as links, every act as a form, every schedule as a
 * button when ticks are exposed. Nothing in it is written per spec.
 */
@RestController
@ConditionalOnProperty(prefix = "velle.console", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class VelleConsole(private val props: VelleProperties) {

    private val page: String by lazy {
        val html = VelleConsole::class.java.getResourceAsStream("/velle/console.html")!!.bufferedReader().readText()
        html.replace("__API__", props.api.path).replace("__TICKS__", props.api.ticks.toString())
    }

    @GetMapping("\${velle.console.path:/velle}", produces = [MediaType.TEXT_HTML_VALUE])
    fun console(): ResponseEntity<String> = ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page)
}
