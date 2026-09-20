package com.dopachiru.data

import com.dopachiru.core.DopaCore
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.model.ActionSpec
import com.dopachiru.core.model.Clause
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.data.db.RuleEntity
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ルールの組が、保存して読み直しても壊れないこと。
 *
 * 組の番号は**数える財布の鍵**なので、往復でずれると持ち時間が別の財布に移ります。
 */
class RuleClausePersistenceTest {

    @Before
    fun setUp() = DopaCore.registerAll()

    private val youtube = Target(packages = setOf("com.google.android.youtube"))

    private fun rule(extra: List<Clause> = emptyList(), overlays: List<ActionSpec> = emptyList()) = Rule(
        id = 3,
        uid = "r-3",
        name = "YouTube",
        target = youtube,
        condition = ConditionNode.AllOf(emptyList()),
        actionId = BlockAction.id,
        actionParams = Params.EMPTY,
        extraClauses = extra,
        extraActions = overlays,
    )

    private fun roundTrip(rule: Rule): Rule =
        rule.toEntity(createdAt = 1, updatedAt = 2).toRule()

    @Test
    fun `組を持たないルールは今までどおり`() {
        val back = roundTrip(rule())
        assertTrue(back.extraClauses.isEmpty())
        assertEquals(1, back.clauses.size)
    }

    @Test
    fun `2組目が往復しても番号と中身が変わらない`() {
        val extra = Clause(
            id = 7,
            condition = ConditionNode.AllOf(emptyList()),
            actions = listOf(ActionSpec(DelayAction.id, Params.of("seconds" to 5))),
            name = "夜のぶん",
        )
        val back = roundTrip(rule(extra = listOf(extra)))

        assertEquals(1, back.extraClauses.size)
        assertEquals(7, back.extraClauses.first().id)
        assertEquals("夜のぶん", back.extraClauses.first().name)
        assertEquals(DelayAction.id, back.extraClauses.first().mainAction?.actionId)
        assertEquals(5, back.extraClauses.first().mainAction?.params?.int("seconds", 0))
    }

    @Test
    fun `重ねる動作も往復する`() {
        val back = roundTrip(rule(overlays = listOf(ActionSpec(WarnAction.id))))
        assertEquals(listOf(WarnAction.id), back.extraActions.map { it.actionId })
        // 1組目の actions は「主 + 重ねるぶん」
        assertEquals(2, back.clauses.first().actions.size)
    }

    @Test
    fun `空のときは空文字で書く`() {
        // "[]" を書くと、組を持たない行と持つ行が見分けづらい
        val entity = rule().toEntity(createdAt = 1, updatedAt = 2)
        assertEquals("", entity.extraClausesJson)
        assertEquals("", entity.extraActionsJson)
    }

    @Test
    fun `壊れた組は読み飛ばしてルールを守る`() {
        // 2組目が読めないくらいでルール全体を失うのは割に合わない
        val broken = rule().toEntity(createdAt = 1, updatedAt = 2)
            .copy(extraClausesJson = "{壊れている")
        val back = broken.toRule()
        assertTrue(back.extraClauses.isEmpty())
        assertEquals(BlockAction.id, back.actionId)
    }

    @Test
    fun `古い行はそのまま1組として読める`() {
        // 移行で足した列は既定が空文字。既存の行はこの形で入っている
        val legacy = RuleEntity(
            id = 1,
            uid = "old",
            name = "むかしのルール",
            targetJson = DopaCore.json.encodeToString(Target.serializer(), youtube),
            conditionJson = DopaCore.encodeCondition(ConditionNode.AllOf(emptyList())),
            actionId = BlockAction.id,
            actionParamsJson = Params.EMPTY.encode(),
            createdAt = 1,
            updatedAt = 1,
        )
        val back = legacy.toRule()
        assertEquals(1, back.clauses.size)
        assertEquals(BlockAction.id, back.clauses.first().mainAction?.actionId)
    }
}
