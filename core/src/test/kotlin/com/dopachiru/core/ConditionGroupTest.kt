package com.dopachiru.core

import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.condition.types.UsageBudgetCondition
import com.dopachiru.core.condition.types.UsageSinceBreakCondition
import com.dopachiru.core.condition.types.WindowBudgetCondition
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 条件の仲間分け。
 *
 * ここの主役は **付け忘れを見つけること**。条件を1つ足したときに仲間と例を
 * 書き忘れても、コンパイルは通ってしまう(どちらも既定値を持つ)。
 * 選ぶ画面で「条件なし」の棚に知らないものが紛れて初めて気づく、では遅い。
 */
class ConditionGroupTest {

    @Before
    fun setUp() = DopaCore.registerAll()

    @Test
    fun `条件なしの棚に入るのは always だけ`() {
        // 既定が ALWAYS なので、ここが増えていたら仲間の付け忘れ
        val ids = ConditionRegistry.selectable()
            .filter { it.group == ConditionGroup.ALWAYS }
            .map { it.id }
        assertEquals(listOf("always"), ids, "仲間を書き忘れた条件がある: $ids")
    }

    @Test
    fun `全部の条件に例が書いてある`() {
        val missing = ConditionRegistry.selectable().filter { it.example.isBlank() }.map { it.id }
        assertTrue(missing.isEmpty(), "例を書き忘れた条件がある: $missing")
    }

    @Test
    fun `仲間に分けても全部の条件が出てくる`() {
        // 束ね方を変えたときに、どこにも属さない条件が消えるのが怖い
        val grouped = ConditionRegistry.byGroup().flatMap { it.second }.map { it.id }.toSet()
        val selectable = ConditionRegistry.selectable().map { it.id }.toSet()
        assertEquals(selectable, grouped)
    }

    @Test
    fun `空の棚は出さない`() {
        assertTrue(ConditionRegistry.byGroup().all { it.second.isNotEmpty() })
    }

    @Test
    fun `使いすぎが先頭に来る`() {
        // 「n分使ったらm分休む」がこの道具の芯。そこへ辿り着くまでに他を見せない
        assertEquals(ConditionGroup.USAGE, ConditionRegistry.byGroup().first().first)
    }

    @Test
    fun `芯は使いすぎの棚にある`() {
        // 「n分使ったらm分休む」がこの道具の芯。3つに分かれていたのを1つにまとめた
        val usage = ConditionRegistry.byGroup().first { it.first == ConditionGroup.USAGE }.second
        assertTrue(usage.any { it.id == UsageBudgetCondition.id })
    }

    @Test
    fun `連続使用防止の棚は使いすぎたら1つだけ`() {
        // 1つだけなら選ぶ画面は棚を開かずにそのまま設定へ進む。
        // 何かを足すとその一発が効かなくなるので、足すなら画面側も見直すこと
        val usage = ConditionRegistry.byGroup().first { it.first == ConditionGroup.USAGE }.second
        assertEquals(listOf(UsageBudgetCondition.id), usage.map { it.id })
    }

    @Test
    fun `開いた回数は開く癖の棚にある`() {
        val trigger = ConditionRegistry.byGroup().first { it.first == ConditionGroup.TRIGGER }.second
        assertTrue(trigger.any { it.id == "session_count" })
    }

    @Test
    fun `連続使用時間はもう選べないが読める`() {
        assertFalse("continuous_usage" in ConditionRegistry.selectable().map { it.id })
        assertTrue(ConditionRegistry["continuous_usage"] != null)
    }

    @Test
    fun `雛形は凍結した条件を使わない`() {
        // 凍結した条件で新しいルールを作れてしまうと、凍結した意味が無い
        val frozen = ConditionRegistry.all().filterNot { it.available }.map { it.id }.toSet()
        val used = com.dopachiru.core.preset.RulePresets.all.flatMap { preset ->
            preset.build(setOf("com.example")).clauses.flatMap { leafIds(it.condition) }
        }
        assertTrue(used.none { it in frozen }, "凍結した条件を使う雛形がある: ${used.filter { it in frozen }}")
    }

    private fun leafIds(node: com.dopachiru.core.model.ConditionNode): List<String> = when (node) {
        is com.dopachiru.core.model.ConditionNode.Leaf -> listOf(node.typeId)
        is com.dopachiru.core.model.ConditionNode.AllOf -> node.children.flatMap { leafIds(it) }
        is com.dopachiru.core.model.ConditionNode.AnyOf -> node.children.flatMap { leafIds(it) }
        is com.dopachiru.core.model.ConditionNode.Not -> leafIds(node.child)
    }

    @Test
    fun `まとめた3つはもう選べない`() {
        // 実装は残す(移行していない保存を読むため)が、選ぶ画面には出さない。
        // 同じことができる条件が4つ並ぶと、何を選べばいいのか分からなくなる
        val selectable = ConditionRegistry.selectable().map { it.id }
        assertFalse(UsageSinceBreakCondition.id in selectable)
        assertFalse(WindowBudgetCondition.id in selectable)
        assertFalse("total_usage" in selectable)
    }

    // ---- 探す ----------------------------------------------------------

    @Test
    fun `名前で探せる`() {
        assertTrue(ConditionRegistry.search("使いすぎ").any { it.id == UsageBudgetCondition.id })
    }

    @Test
    fun `例文の言葉でも探せる`() {
        // 名前を思い出せなくても、やりたいことの言葉で辿り着けるように
        assertTrue(ConditionRegistry.search("ショート").any { it.id == "on_screen" })
        assertTrue(ConditionRegistry.search("平日").any { it.id == "day_of_week" })
    }

    @Test
    fun `IDでも探せる`() {
        assertTrue(ConditionRegistry.search("usage_budget").any { it.id == UsageBudgetCondition.id })
    }

    @Test
    fun `空なら全部返す`() {
        assertEquals(ConditionRegistry.selectable().size, ConditionRegistry.search("  ").size)
    }

    @Test
    fun `当たらなければ空`() {
        assertTrue(ConditionRegistry.search("そんな条件は無い").isEmpty())
    }
}
