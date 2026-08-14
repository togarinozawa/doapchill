package com.dopachiru.core

import com.dopachiru.core.condition.types.ContinuousUsageCondition
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConditionTreeTest {

    private val engine = RuleEngine()

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun ctx(now: LocalDateTime, sessionMinutes: Int = 0) = EvalContext(
        now = now,
        packageName = "com.example.sns",
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = sessionMinutes
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        },
    )

    private fun night() = ConditionNode.Leaf(
        TimeRangeCondition.id,
        Params.of(TimeRangeCondition.KEY_START to 22 * 60, TimeRangeCondition.KEY_END to 6 * 60),
    )

    private fun longSession(minutes: Int = 15) = ConditionNode.Leaf(
        ContinuousUsageCondition.id,
        Params.of(ContinuousUsageCondition.KEY_MINUTES to minutes),
    )

    // ------------------------------------------------------------------

    @Test
    fun `OR はどちらか成立すればよい`() {
        val tree = ConditionNode.AnyOf(listOf(night(), longSession()))
        // 昼だが連続20分
        assertTrue(engine.evaluate(tree, ctx(LocalDateTime.of(2026, 8, 6, 12, 0), sessionMinutes = 20)))
        // 夜だが開いたばかり
        assertTrue(engine.evaluate(tree, ctx(LocalDateTime.of(2026, 8, 6, 23, 0), sessionMinutes = 0)))
        // どちらでもない
        assertFalse(engine.evaluate(tree, ctx(LocalDateTime.of(2026, 8, 6, 12, 0), sessionMinutes = 0)))
    }

    @Test
    fun `NOT は成否を裏返す`() {
        val tree = ConditionNode.Not(night())
        assertFalse(engine.evaluate(tree, ctx(LocalDateTime.of(2026, 8, 6, 23, 0))))
        assertTrue(engine.evaluate(tree, ctx(LocalDateTime.of(2026, 8, 6, 12, 0))))
    }

    @Test
    fun `AND の中に OR を入れ子にできる`() {
        // 夜 かつ (連続15分 または 連続60分) → 実質「夜 かつ 連続15分」
        val tree = ConditionNode.AllOf(
            listOf(
                night(),
                ConditionNode.AnyOf(listOf(longSession(15), longSession(60))),
            )
        )
        assertTrue(engine.evaluate(tree, ctx(LocalDateTime.of(2026, 8, 6, 23, 0), sessionMinutes = 20)))
        assertFalse(engine.evaluate(tree, ctx(LocalDateTime.of(2026, 8, 6, 23, 0), sessionMinutes = 5)))
        assertFalse(engine.evaluate(tree, ctx(LocalDateTime.of(2026, 8, 6, 12, 0), sessionMinutes = 20)))
    }

    // ------------------------------------------------------------------

    @Test
    fun `添字は否定を透かして数える`() {
        val tree = ConditionNode.Not(ConditionNode.AllOf(listOf(night(), longSession())))
        assertEquals(night(), ConditionTree.nodeAt(tree, listOf(0)))
        assertEquals(longSession(), ConditionTree.nodeAt(tree, listOf(1)))
        assertTrue(ConditionTree.isNegated(tree))
    }

    @Test
    fun `二重否定は打ち消される`() {
        val doubled = ConditionNode.Not(ConditionNode.Not(night()))
        val (inner, negated) = ConditionTree.stripNot(doubled)
        assertEquals(night(), inner)
        assertFalse(negated)
    }

    @Test
    fun `子を足しても親の否定と繋ぎ方が残る`() {
        val tree: ConditionNode = ConditionNode.Not(ConditionNode.AnyOf(listOf(night())))
        val added = ConditionTree.addChild(tree, emptyList(), longSession())

        assertTrue(ConditionTree.isNegated(added))
        assertFalse(ConditionTree.isAll(added))
        assertEquals(listOf(night(), longSession()), ConditionTree.childrenOf(added))
    }

    @Test
    fun `入れ子の奥にある葉を差し替えられる`() {
        val tree = ConditionNode.AllOf(
            listOf(night(), ConditionNode.AnyOf(listOf(longSession(15), longSession(60))))
        )
        val updated = ConditionTree.setParams(
            tree,
            listOf(1, 0),
            Params.of(ContinuousUsageCondition.KEY_MINUTES to 99),
        )

        assertEquals(longSession(99), ConditionTree.nodeAt(updated, listOf(1, 0)))
        // 触っていないところは元のまま
        assertEquals(night(), ConditionTree.nodeAt(updated, listOf(0)))
        assertEquals(longSession(60), ConditionTree.nodeAt(updated, listOf(1, 1)))
    }

    @Test
    fun `入れ子の奥にある葉を消せる`() {
        val tree = ConditionNode.AllOf(
            listOf(night(), ConditionNode.AnyOf(listOf(longSession(15), longSession(60))))
        )
        val removed = ConditionTree.removeAt(tree, listOf(1, 0))

        assertEquals(longSession(60), ConditionTree.nodeAt(removed, listOf(1, 0)))
        assertNull(ConditionTree.nodeAt(removed, listOf(1, 1)))
        assertEquals(2, ConditionTree.leafCount(removed))
    }

    @Test
    fun `AND と OR を入れ替えても子は残る`() {
        val tree = ConditionNode.AllOf(listOf(night(), longSession()))
        val swapped = ConditionTree.setAll(tree, emptyList(), all = false)

        assertFalse(ConditionTree.isAll(swapped))
        assertEquals(listOf(night(), longSession()), ConditionTree.childrenOf(swapped))
    }

    @Test
    fun `否定を付け外ししても中身は変わらない`() {
        val tree: ConditionNode = ConditionNode.AllOf(listOf(night()))
        val negated = ConditionTree.setNegated(tree, emptyList(), true)
        assertTrue(ConditionTree.isNegated(negated))
        assertEquals(tree, ConditionTree.setNegated(negated, emptyList(), false))
    }

    @Test
    fun `無い位置を触っても木は壊れない`() {
        val tree: ConditionNode = ConditionNode.AllOf(listOf(night()))
        assertEquals(tree, ConditionTree.removeAt(tree, listOf(5)))
        assertEquals(tree, ConditionTree.replaceAt(tree, listOf(3, 1), longSession()))
        // 葉に子は足せない
        assertEquals(tree, ConditionTree.addChild(tree, listOf(0), longSession()))
        assertNull(ConditionTree.nodeAt(tree, listOf(0, 0)))
    }

    @Test
    fun `一行の説明に入れ子が括弧で出る`() {
        val tree = ConditionNode.AllOf(
            listOf(night(), ConditionNode.AnyOf(listOf(longSession(15), longSession(60))))
        )
        val text = ConditionTree.describe(tree)

        assertTrue(text.contains("かつ"), text)
        assertTrue(text.contains("または"), text)
        assertTrue(text.contains("("), text)
    }

    @Test
    fun `否定した木もJSONで往復できる`() {
        val tree: ConditionNode = ConditionNode.Not(
            ConditionNode.AnyOf(listOf(night(), ConditionNode.Not(longSession())))
        )
        assertEquals(tree, DopaCore.decodeCondition(DopaCore.encodeCondition(tree)))
    }
}
