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

    private val schema: JsonObject by lazy {
        val file = File("schemas/com.dopachiru.data.db.DopaDatabase/4.json")
        assertTrue(file.exists(), "スキーマが見つからない: ${file.absolutePath}")
        Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

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

    @Test
    fun `移行に余計な文が混ざっていない`() {
        val expected = buildList {
            add("ALTER TABLE")
            addAll(listOf("lockouts", "point_events").flatMap { listOf(createSqlOf(it)) + indexSqlOf(it) })
        }
        assertEquals(expected.size, DopaDatabase.MIGRATION_3_4_SQL.size)
    }
}
