package velle.api.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.ZoneId

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
 *   ticker:
 *     enabled: true       # run the schedules below on this instance; false leaves the cadence to another instance
 *   schedules:            # when each schedule the spec names ticks; an unnamed schedule never ticks
 *     Hourly: "0 0 * * * *"
 *     Weekly:
 *       cron: "0 0 6 * * MON"
 *       zone: America/New_York
 *   console:
 *     enabled: true       # serve the console page
 *     path: /velle
 * ```
 */
@ConfigurationProperties("velle")
class VelleProperties {
    var api = Api()
    var store = Store()
    var ticker = Ticker()
    var schedules: Map<String, Schedule> = emptyMap()
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

    class Ticker {
        /**
         * Whether this instance runs the schedules under `velle.schedules`. Off, nothing else
         * changes: the ticks endpoint, the GraphQL `tick` mutation, and the generated
         * `tick<Schedule>()` calls still tick this instance's system.
         */
        var enabled: Boolean = true
    }

    /**
     * When one schedule ticks: a cron expression, read in a time zone. Written as the
     * expression alone, `Weekly: "0 0 6 * * MON"`, or as `cron` and `zone`. Without a
     * zone the expression is read in the JVM's default zone.
     */
    class Schedule() {
        /** Spring's cron expression: six fields, seconds first, or a macro such as `@daily`. */
        var cron: String = ""
        /** The zone the expression is read in; the JVM's default when absent. */
        var zone: ZoneId? = null

        /** The one-line form: the expression alone. Boot's binder reaches this constructor from a string. */
        constructor(cron: String) : this() {
            this.cron = cron
        }
    }

    class Console {
        var enabled: Boolean = true
        var path: String = "/velle"
    }
}
