package velle.sqlite

import velle.sql.snakeCase
import kotlin.test.Test
import kotlin.test.assertEquals

/** The name the SQL store gives a Velle name in the database (`velle.sql.snakeCase`). */
class SnakeCaseTest {

    @Test
    fun `a capital starts a word`() {
        assertEquals("due_on", snakeCase("dueOn"))
        assertEquals("archived_invoice", snakeCase("ArchivedInvoice"))
        assertEquals("student", snakeCase("Student"))
        assertEquals("name", snakeCase("name"))
    }

    @Test
    fun `a run of capitals is one word, its last capital starting the next word before a lower-case letter`() {
        assertEquals("http_server", snakeCase("HTTPServer"))
        assertEquals("path_url", snakeCase("pathURL"))
        assertEquals("path_url", snakeCase("pathUrl"))
        assertEquals("u_rl_path", snakeCase("uRLPath"))
    }

    @Test
    fun `a digit ends a word only where a capital follows it`() {
        assertEquals("address2_line", snakeCase("address2Line"))
        assertEquals("line2", snakeCase("Line2"))
    }
}
