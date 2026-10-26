package velle.api.spring

import jakarta.servlet.http.HttpServletRequest
import velle.Ref

/**
 * How the application turns an authenticated request into the actor: a reference
 * to the record of the person committing or reading ([Authorization]). Declared
 * once as a bean; called once per request, before the REST controller or the
 * GraphQL engine runs; the result rides the request. Return null when the request
 * carries no identity. Velle never authenticates: the resolver reads what the
 * application's own security filter established, a principal or a header, and
 * finds the record.
 */
fun interface ActorResolver {
    fun actor(request: HttpServletRequest): Ref<*>?
}
