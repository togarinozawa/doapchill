package com.dopachiru.core.model

import com.dopachiru.core.DopaCore
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.param.Params
import kotlinx.serialization.json.JsonObject
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

class RuleTest {

    @Before
    fun setUp() = DopaCore.registerAll()

    @Test
    fun `端末の指定を保ったまま読み書きできる`() {
        val rule = Rule(
            name = "夜はSNS",
            target = Target(packages = setOf("com.example.sns")),
            condition = ConditionNode.AllOf(emptyList()),
            actionId = BlockAction.id,
            actionParams = Params.defaultsOf(BlockAction.params),
            devices = setOf("pc"),
        )
        val json = DopaCore.json.encodeToJsonElement(Rule.serializer(), rule)
        val back = DopaCore.json.decodeFromJsonElement(Rule.serializer(), json)
        assertTrue(back.devices == setOf("pc"))
    }

    @Test
    fun `端末の指定が無い古いルールも読める`() {
        // 欄を足す前に保存されたものは、この欄を持っていない。
        // 既定が空(= どの端末でも効く)なので、縛りが勝手に外れることはない
        val bare = DopaCore.json.parseToJsonElement(
            """{"name":"むかしのルール","target":{},"condition":{"kind":"allOf","children":[]},"actionId":"block"}""",
        ) as JsonObject
        val back = DopaCore.json.decodeFromJsonElement(Rule.serializer(), bare)
        assertTrue(back.devices.isEmpty())
        assertTrue(back.appliesToDevice("どの端末でも"))
    }
}
