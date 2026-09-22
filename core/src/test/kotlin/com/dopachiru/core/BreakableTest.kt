package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.RadioAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.model.RuleCheck
import com.dopachiru.core.param.Params
import org.junit.Test
import kotlin.test.assertFalse
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
}
