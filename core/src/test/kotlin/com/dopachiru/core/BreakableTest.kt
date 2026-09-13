package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.RadioAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.LockScope
import com.dopachiru.core.model.RuleCheck
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「どうする(措置)」と「破ったら(報い)」の噛み合わせ。
 *
 * 破る道の無い措置に罰を付けても何も起きない ── 画面がそれを隠さないための土台。
 */
class BreakableTest {

    @Test
    fun `完全封印は押し切りを許したときだけ破れる`() {
        val canOverride = Params.of(BlockAction.KEY_ALLOW_OVERRIDE to true)
        val cannot = Params.of(BlockAction.KEY_ALLOW_OVERRIDE to false)

        assertTrue(RuleCheck.isBreakable(BlockAction.id, canOverride))
        assertFalse(RuleCheck.isBreakable(BlockAction.id, cannot))
    }

    @Test
    fun `既定の完全封印は破れる`() {
        // 既定は押し切りを許す側。空の Params でもそう読めること
        assertTrue(RuleCheck.isBreakable(BlockAction.id, Params.EMPTY))
    }

    @Test
    fun `警告と宣言は破れる`() {
        assertTrue(RuleCheck.isBreakable(WarnAction.id, Params.EMPTY))
        assertTrue(RuleCheck.isBreakable(DeclareAction.id, Params.EMPTY))
    }

    @Test
    fun `閉め出しと音だけは破りようがない`() {
        // 時間が来るまで解けないので、押し切るという出来事が起きない
        assertFalse(RuleCheck.isBreakable(LockoutAction.id, Params.EMPTY))
        assertFalse(RuleCheck.isBreakable(RadioAction.id, Params.EMPTY))
    }

    @Test
    fun `押し切れない封印は罰が科されないと書く`() {
        val cannot = Params.of(BlockAction.KEY_ALLOW_OVERRIDE to false)
        assertTrue(RuleCheck.breakMeans(BlockAction.id, cannot).contains("科されません"))
    }

    // ---- 選んだものだけ閉める -------------------------------------------

    @Test
    fun `選んだものだけ閉める`() {
        // 破ったアプリと閉まるものを別にできる
        val consequence = Consequence(
            lockScope = LockScope.CUSTOM,
            lockMinutes = 30,
            lockTarget = Target(tags = setOf("sns")),
        )
        val locked = consequence.resolveTarget("com.example.twitter", Target(packages = setOf("com.example.twitter")))
        assertTrue(locked!!.matches("com.example.instagram", setOf("sns")))
        assertFalse(locked.matches("com.example.work", emptySet()))
    }

    @Test
    fun `選び忘れたら何も閉めない`() {
        // 空の範囲を「全部」に倒すと、選び忘れだけで端末が閉まる
        val consequence = Consequence(lockScope = LockScope.CUSTOM, lockMinutes = 30, lockTarget = null)
        assertNull(consequence.resolveTarget("com.example.a", Target(matchAll = true)))

        val empty = consequence.copy(lockTarget = Target())
        assertNull(empty.resolveTarget("com.example.a", Target(matchAll = true)))
    }

    @Test
    fun `既存の範囲は変わらない`() {
        // CUSTOM を足したことで、前からある3つの意味が変わっていないこと
        val ruleTarget = Target(packages = setOf("com.example.x"))
        assertEquals(
            setOf("com.example.broke"),
            Consequence(lockScope = LockScope.APP, lockMinutes = 5)
                .resolveTarget("com.example.broke", ruleTarget)!!.packages,
        )
        assertEquals(
            ruleTarget,
            Consequence(lockScope = LockScope.RULE_TARGET, lockMinutes = 5)
                .resolveTarget("com.example.broke", ruleTarget),
        )
        assertTrue(
            Consequence(lockScope = LockScope.EVERYTHING, lockMinutes = 5)
                .resolveTarget("com.example.broke", ruleTarget)!!.matchAll,
        )
    }
}
