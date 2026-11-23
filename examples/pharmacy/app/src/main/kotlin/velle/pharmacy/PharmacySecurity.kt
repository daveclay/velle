package velle.pharmacy

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.client.RestClient

/**
 * Who may reach the application at all: whoever has signed in at the identity
 * provider. The spec decides what each signed-in member of staff may do and see;
 * this decides only that there is someone.
 *
 * - A browser signs in through OpenID Connect and keeps the session in a cookie.
 *   The console's requests to `/api` and `/graphql` carry that cookie.
 * - A script or a test sends the provider's token as `Authorization: Bearer ...`.
 * - A request with neither is answered 401 under `/api` and `/graphql`, and sent
 *   to the provider's sign-in page anywhere else.
 * - `/logout` ends the session here and at the provider, so the next sign-in
 *   may be someone else.
 * - The code a browser brings back from the provider is exchanged for tokens
 *   over HTTP/1.1. Spring Security's default client offers to upgrade to HTTP/2,
 *   and Authentik refuses the request.
 *
 * Cross-site request forgery: `/api` and `/graphql` accept a POST without a
 * forgery token, because the console sends none. The session cookie is
 * `SameSite=Lax` (application.yaml), so a page on another site cannot make the
 * browser send it with a POST.
 */
@Configuration(proxyBeanMethods = false)
class PharmacySecurity {

    @Bean
    fun signIn(http: HttpSecurity, @Value("\${velle.identity-provider}") identityProvider: String): SecurityFilterChain {
        val apiRequests = RequestMatcher { request ->
            val path = request.requestURI.removePrefix(request.contextPath)
            path == "/api" || path.startsWith("/api/") || path == "/graphql"
        }
        val signOutAtTheProvider = SimpleUrlLogoutSuccessHandler()
        signOutAtTheProvider.setDefaultTargetUrl("$identityProvider/application/o/pharmacy/end-session/")
        http
            .authorizeHttpRequests { requests -> requests.anyRequest().authenticated() }
            .oauth2Login { login -> login.tokenEndpoint { endpoint -> endpoint.accessTokenResponseClient(tokenExchangeOverHttp11()) } }
            .oauth2ResourceServer { resourceServer -> resourceServer.jwt { } }
            .exceptionHandling { exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), apiRequests)
                exceptions.defaultAuthenticationEntryPointFor(LoginUrlAuthenticationEntryPoint("/oauth2/authorization/authentik")) { true }
            }
            .csrf { csrf -> csrf.ignoringRequestMatchers(apiRequests) }
            .logout { logout -> logout.logoutSuccessHandler(signOutAtTheProvider) }
        return http.build()
    }

    private fun tokenExchangeOverHttp11(): RestClientAuthorizationCodeTokenResponseClient {
        val overHttp11 = RestClient.builder()
            .requestFactory(SimpleClientHttpRequestFactory())
            .messageConverters { converters ->
                converters.clear()
                converters.add(FormHttpMessageConverter())
                converters.add(OAuth2AccessTokenResponseHttpMessageConverter())
            }
            .defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
            .build()
        val tokenExchange = RestClientAuthorizationCodeTokenResponseClient()
        tokenExchange.setRestClient(overHttp11)
        return tokenExchange
    }
}
