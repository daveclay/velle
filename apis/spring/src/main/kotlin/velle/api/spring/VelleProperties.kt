package velle.api.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * What the application configures under `velle.*`:
 *
 * ```yaml
 * velle:
 *   api:
 *     path: /api          # where the REST endpoints live
 *     ticks: false        # expose POST {path}/ticks/{schedule} and the GraphQL `tick` mutation
 *   store:
 *     enabled: true       # connect the system to the DataSource through the SQL store
 *     create-schema: true # create the tables at startup when they do not exist
 *   schedules:            # a cron expression per schedule the spec names; unnamed schedules never tick
 *     Hourly: "0 0 * * * *"
 *     Weekly: "0 0 6 * * MON"
 *   console:
 *     enabled: true       # serve the console page
 *     path: /velle
 * ```
 */
@ConfigurationProperties("velle")
class VelleProperties {
    var api = Api()
    var store = Store()
    var schedules: Map<String, String> = emptyMap()
    var console = Console()

    class Api {
        /** The path the REST endpoints hang from. */
        var path: String = "/api"
        /** Whether a caller may fire a tick over HTTP and GraphQL. Off unless the deployment wants it. */
        var ticks: Boolean = false
    }

    class Store {
        /** Connect the system to the application's DataSource through the SQL store. */
        var enabled: Boolean = true
        /** Create the model's tables at startup when they do not exist yet. */
        var createSchema: Boolean = true
    }

    class Console {
        var enabled: Boolean = true
        var path: String = "/velle"
    }
}
