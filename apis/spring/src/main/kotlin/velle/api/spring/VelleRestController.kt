package velle.api.spring

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import velle.api.ApiException
import velle.api.VelleApi

/**
 * The REST endpoints, one set for every spec, under `velle.api.path` (`/api`):
 *
 * | | |
 * |---|---|
 * | `GET  /api` | the model: acts, shapes, schedules, authorization |
 * | `POST /api/acts/{Act}` | commit the act with the JSON body as its input; 200 accepted, 422 refused, 400 malformed |
 * | `GET  /api/shapes/{Shape}/{key}` | one record; 404 when gone, not a member, or not shown to the actor |
 * | `GET  /api/shapes/{Shape}?where=&orderBy=&size=&after=` | a query in the spec's predicate language, ordered and paged |
 * | `GET  /api/shapes/{Shape}/count?where=` | how many |
 * | `POST /api/ticks/{Schedule}` | run the schedule's rules now, when `velle.api.ticks` is on |
 *
 * `{Shape}` is a shape or a refinement. `orderBy` is repeated or comma-separated,
 * `-due` for descending. The actor is whatever the application's [ActorResolver]
 * found for the request; 401 when the system needs one and none was found.
 */
@RestController
@RequestMapping("\${velle.api.path:/api}")
class VelleRestController(private val api: VelleApi, private val props: VelleProperties) {

    @GetMapping
    fun describe(): Map<String, Any?> = api.description

    @PostMapping("/acts/{act}")
    fun commit(@PathVariable act: String, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val outcome = api.commit(act, body, VelleAutoConfiguration.actorOf(request))
        val status = if (outcome["accepted"] == true) HttpStatus.OK else HttpStatus.UNPROCESSABLE_CONTENT
        return ResponseEntity.status(status).body(outcome)
    }

    @GetMapping("/shapes/{root}/{key}")
    fun read(@PathVariable root: String, @PathVariable key: String, request: HttpServletRequest): Map<String, Any?> =
        api.read(root, key, VelleAutoConfiguration.actorOf(request))

    @GetMapping("/shapes/{root}")
    fun query(
        @PathVariable root: String,
        @RequestParam(required = false) where: String?,
        @RequestParam(required = false) orderBy: List<String>?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) after: String?,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val order = orderBy.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        return api.query(root, where, order, size, after, VelleAutoConfiguration.actorOf(request))
    }

    @GetMapping("/shapes/{root}/count")
    fun count(@PathVariable root: String, @RequestParam(required = false) where: String?, request: HttpServletRequest): Map<String, Any?> =
        mapOf("count" to api.count(root, where, VelleAutoConfiguration.actorOf(request)))

    @PostMapping("/ticks/{schedule}")
    fun tick(@PathVariable schedule: String): Map<String, Any?> {
        if (!props.api.ticks) throw ApiException.notFound("ticks are not exposed over HTTP; set velle.api.ticks=true to allow it")
        return api.tick(schedule)
    }

    @ExceptionHandler(ApiException::class)
    fun refused(e: ApiException): ResponseEntity<Map<String, Any?>> {
        val status = when (e.kind) {
            ApiException.Kind.BAD_REQUEST -> HttpStatus.BAD_REQUEST
            ApiException.Kind.NOT_FOUND -> HttpStatus.NOT_FOUND
            ApiException.Kind.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
        }
        return ResponseEntity.status(status).body(mapOf("error" to e.message, "details" to e.details))
    }
}
