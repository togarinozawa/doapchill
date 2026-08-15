package com.dopachiru.core

import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.preset.PresetGroup
import com.dopachiru.core.preset.RulePresets
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 雛形が「そのまま動くルール」になっているか。
 *
 * 雛形は一番よく使われる入口なので、ここが壊れていると
 * 何を作っても最初から効かない。作れること自体を通しで見る。
 */
class RulePresetTest {

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private val apps = setOf("com.example.sns", "com.example.video")

    @Test
    fun `すべての雛形がルールを組み立てられる`() {
        RulePresets.all.forEach { preset ->
            val rule = preset.build(apps)
            assertTrue(rule.name.isNotBlank(), "${preset.id}: 名前が空")
            assertNotNull(
                ActionRegistry[rule.actionId],
                "${preset.id}: 未登録のアクション ${rule.actionId}",
            )
        }
    }

    @Test
    fun `雛形が使う条件はすべて登録されている`() {
        RulePresets.all.forEach { preset ->
            ConditionTree.leafTypeIds(preset.build(apps).condition).forEach { typeId ->
                assertNotNull(ConditionRegistry[typeId], "${preset.id}: 未登録の条件 $typeId")
            }
        }
    }

    @Test
    fun `雛形はJSONで往復できる`() {
        RulePresets.all.forEach { preset ->
            val rule = preset.build(apps)
            assertEquals(rule, DopaCore.decodeRule(DopaCore.encodeRule(rule)), preset.id)
        }
    }

    @Test
    fun `雛形のIDは重複していない`() {
        val ids = RulePresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "重複: $ids")
    }

    @Test
    fun `アプリを選ばせる雛形は選ばないと対象が空になる`() {
        // 対象が空のルールは何にも当たらない。保存させないための目印が
        // allowEmptyApps なので、逆になっていないかを見る
        RulePresets.all.filterNot { it.allowEmptyApps }.forEach { preset ->
            val rule = preset.build(apps)
            val usesApps = rule.target.packages.isNotEmpty() ||
                rule.target.matchAll ||
                // 「直前のアプリ」型は対象ではなく条件側でアプリを使う
                ConditionTree.leafTypeIds(rule.condition).contains("app_chain")
            assertTrue(usesApps, "${preset.id}: 選んだアプリがどこにも使われていない")
        }
    }

    @Test
    fun `軽い雛形が少なくとも3つある`() {
        // 強いものから始めると続かない。入口になる軽い選択肢が要る
        val gentle = RulePresets.all.count { it.group == PresetGroup.GENTLE }
        assertTrue(gentle >= 3, "軽い雛形が $gentle 個しかない")
    }

    @Test
    fun `強い雛形には押し切れる逃げ道か根拠がある`() {
        RulePresets.all.filter { it.group == PresetGroup.STRICT }.forEach { preset ->
            assertTrue(
                preset.description.isNotBlank(),
                "${preset.id}: 強い措置は何が起きるか書いておく",
            )
        }
    }
}
