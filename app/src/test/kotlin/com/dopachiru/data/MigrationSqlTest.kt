package com.dopachiru.data

import com.dopachiru.data.db.DopaDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 手書きの移行 SQL と、Room が生成したスキーマを突き合わせる。
 *
 * 移行が実際に走るのは更新した端末の上だけで、ずれていても
 * **その場で気づけない**。気づいたときには手元のデータが人質になっている。
 * 生成物と一字一句そろっているかを、機械に見比べさせておく。
 */
class MigrationSqlTest {

    private fun schemaOf(version: Int): JsonObject {
        val file = File("schemas/com.dopachiru.data.db.DopaDatabase/$version.json")
        assertTrue(file.exists(), "スキーマが見つからない: ${file.absolutePath}")
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private val schema: JsonObject by lazy { schemaOf(4) }

    /** いまの版。移行を足したらここを上げる。 */
    private val latest: JsonObject by lazy { schemaOf(5) }

    private fun createSqlOf(tableName: String): String = entity(tableName)["createSql"]!!
        .jsonPrimitive.content
        .replace("\${TABLE_NAME}", tableName)

    private fun indexSqlOf(tableName: String): List<String> =
        (entity(tableName)["indices"]?.jsonArray ?: return emptyList()).map {
            it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName)
        }

    private fun entity(tableName: String): JsonObject =
        schema["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == tableName }

    @Test
    fun `スキーマの版と database の版がそろっている`() {
        assertEquals(4, schema["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, latest["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `足したテーブルの SQL が生成物と一致する`() {
        val sql = DopaDatabase.MIGRATION_3_4_SQL
        listOf("lockouts", "point_events").forEach { table ->
            assertTrue(
                createSqlOf(table) in sql,
                "$table の CREATE がずれている\n生成: ${createSqlOf(table)}\n移行: $sql",
            )
            indexSqlOf(table).forEach { index ->
                assertTrue(index in sql, "$table の索引がずれている: $index")
            }
        }
    }

    @Test
    fun `足した列の既定値が生成物と一致する`() {
        val field = entity("rules")["fields"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["columnName"]!!.jsonPrimitive.content == "consequenceJson" }

        // ALTER の DEFAULT と Room が期待する既定値がずれていると、
        // 移行そのものは通ってから検証で落ちる
        assertEquals("''", field["defaultValue"]!!.jsonPrimitive.content)
        assertEquals("TEXT", field["affinity"]!!.jsonPrimitive.content)
        assertEquals(true, field["notNull"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            DopaDatabase.MIGRATION_3_4_SQL.any {
                it.startsWith("ALTER TABLE `rules`") &&
                    it.contains("`consequenceJson` TEXT NOT NULL DEFAULT ''")
            }
        )
    }

    // ---- 4 から 5 ---------------------------------------------------------

    private fun fieldOf(version: Int, table: String, column: String): JsonObject =
        schemaOf(version)["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == table }["fields"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["columnName"]!!.jsonPrimitive.content == column }

    @Test
    fun `封鎖に足した2列が生成物と一致する`() {
        listOf("uid", "earlyExitJson").forEach { column ->
            val field = fieldOf(5, "lockouts", column)
            assertEquals("''", field["defaultValue"]!!.jsonPrimitive.content, column)
            assertEquals("TEXT", field["affinity"]!!.jsonPrimitive.content, column)
            assertEquals(true, field["notNull"]!!.jsonPrimitive.content.toBoolean(), column)

            assertTrue(
                DopaDatabase.MIGRATION_4_5_SQL.any {
                    it == "ALTER TABLE `lockouts` ADD COLUMN `$column` TEXT NOT NULL DEFAULT ''"
                },
                "$column の ALTER がずれている: ${DopaDatabase.MIGRATION_4_5_SQL}",
            )
        }
    }

    @Test
    fun `4から5で増えた列はこの2つだけ`() {
        // 列を足したのに移行を書き忘れると、更新した端末でだけ落ちる
        fun columns(version: Int) = schemaOf(version)["entities"]!!.jsonArray
            .map { it.jsonObject }
            .flatMap { e ->
                val table = e["tableName"]!!.jsonPrimitive.content
                e["fields"]!!.jsonArray.map { table + "." + it.jsonObject["columnName"]!!.jsonPrimitive.content }
            }.toSet()

        val added = columns(5) - columns(4)
        assertEquals(setOf("lockouts.uid", "lockouts.earlyExitJson"), added)
        assertEquals(2, DopaDatabase.MIGRATION_4_5_SQL.size)
    }

    @Test
    fun `4から5でテーブルは増えていない`() {
        fun tables(version: Int) = schemaOf(version)["entities"]!!.jsonArray
            .map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        assertEquals(tables(4), tables(5))
    }

    @Test
    fun `移行に余計な文が混ざっていない`() {
        val expected = buildList {
            add("ALTER TABLE")
            addAll(listOf("lockouts", "point_events").flatMap { listOf(createSqlOf(it)) + indexSqlOf(it) })
        }
        assertEquals(expected.size, DopaDatabase.MIGRATION_3_4_SQL.size)
    }
}
