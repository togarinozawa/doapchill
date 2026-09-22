package com.dopachiru.core

import com.dopachiru.core.action.ActionExtras
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.RadioAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.model.ActionPlan
import com.dopachiru.core.model.MainAction
import com.dopachiru.core.param.Params
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「こうする」の UI 形([ActionPlan])と保存形(actionId + params)の往復。
 *
 * Android と Windows が同じ変換を通すための土台なので、ここがずれると
 * 片方で block、片方で lockout に化ける。
 */
class ActionPlanTest {

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    @Test
    fun `閉じる・しっかりは押し切れない封印`() {
        val plan = ActionPlan(main = MainAction.CLOSE, soft = false)
        val (id, params) = plan.resolve(BlockAction.id, Params.EMPTY)
        assertEquals(BlockAction.id, id)
        assertFalse(params.bool(BlockAction.KEY_ALLOW_OVERRIDE, true))
    }

    @Test
    fun `閉じる・やんわりは押し切れる封印`() {
        val plan = ActionPlan(main = MainAction.CLOSE, soft = true)
        val (id, params) = plan.resolve(BlockAction.id, Params.EMPTY)
        assertEquals(BlockAction.id, id)
        assertTrue(params.bool(BlockAction.KEY_ALLOW_OVERRIDE, false))
    }

    @Test
    fun `そっと知らせるは閉じるに乗る`() {
        val block = ActionPlan(main = MainAction.CLOSE, prewarnSeconds = 3).resolve(BlockAction.id, Params.EMPTY)
        assertEquals(3, ActionExtras.prewarnSeconds(block.second))
    }

    @Test
    fun `保存形から起こし直しても同じ計画`() {
        val plans = listOf(
            ActionPlan(main = MainAction.CLOSE, soft = false, prewarnSeconds = 3),
            ActionPlan(main = MainAction.CLOSE, soft = true),
            ActionPlan(main = MainAction.DELAY),
            ActionPlan(main = MainAction.WARN),
            ActionPlan(main = MainAction.ADVANCED, advancedActionId = RadioAction.id),
        )
        for (plan in plans) {
            val (id, params) = plan.resolve("", Params.EMPTY)
            val back = ActionPlan.from(id, params)
            assertEquals(plan.main, back.main, "main for $plan")
            assertEquals(plan.prewarnSeconds, back.prewarnSeconds, "prewarn for $plan")
        }
    }

    @Test
    fun `くわしい動作はそのまま保たれる`() {
        val plan = ActionPlan.from(WarnAction.id, Params.EMPTY)
        assertEquals(MainAction.WARN, plan.main)

        val delay = ActionPlan.from(DelayAction.id, Params.EMPTY)
        assertEquals(MainAction.DELAY, delay.main)

        val radio = ActionPlan.from(RadioAction.id, Params.EMPTY)
        assertEquals(MainAction.ADVANCED, radio.main)
        assertEquals(RadioAction.id, radio.advancedActionId)
    }

    @Test
    fun `以前のタイマー封印は「ほかの動作にする」に落ちる`() {
        // CLOSE+タイマーで作られていた lockout は、ADVANCED として編集を続けられる
        val params = Params.of(LockoutAction.KEY_MINUTES to 20)
        val plan = ActionPlan.from(LockoutAction.id, params)
        assertEquals(MainAction.ADVANCED, plan.main)
        assertEquals(LockoutAction.id, plan.advancedActionId)
    }
}
