package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.types.LinkedRuleCondition
import com.dopachiru.core.condition.types.WindowBudgetCondition
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.engine.WindowUsage
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.RuleLinks
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.sync.RuleState
import com.dopachiru.core.sync.RuleStates
import org.junit.Before
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 端末をまたいだ連動。
 *
 * 肝は3つ。
 *  1. **自分の端末の状態は読まない**(読むと一度成立して永久に外れない)
 *  2. **締め切りを過ぎたら緩む**(向こうが黙ったまま塞がり続けない)
 *  3. **届いていなければ成立しない**(上乗せであって土台ではない)
 */
class LinkedRuleTest {

    @Before
    fun setUp() = DopaCore.registerAll()

    private val engine = RuleEngine()
    private val now = 1_000_000L
    private val youtube = Target(packages = setOf("com.google.android.youtube"))

    private fun ctx(
        linked: (String, String) -> Boolean = { _, _ -> false },
        usage: WindowUsage = WindowUsage.NONE,
    ) = EvalContext(
        now = LocalDateTime.of(2026, 9, 20, 12, 0),
        packageName = "com.google.android.youtube",
        usage = UsageSnapshot.EMPTY,
        linkedActiveOf = linked,
        windowUsageOf = { _, _ -> usage },
    )

    private fun linkedRule(uid: String = "", deviceId: String = "") = Rule(
        id = 1,
        uid = "r-youtube",
        name = "YouTube は持ち時間まで",
        target = youtube,
        condition = ConditionNode.AnyOf(
            listOf(
                ConditionNode.Leaf(
                    WindowBudgetCondition.id,
                    Params.of(
                        WindowBudgetCondition.KEY_WINDOW_MINUTES to 180,
                        WindowBudgetCondition.KEY_BUDGET_MINUTES to 120,
                    ),
                ),
                ConditionNode.Leaf(
                    LinkedRuleCondition.id,
                    Params.of(
                        LinkedRuleCondition.KEY_RULE_UID to uid,
                        LinkedRuleCondition.KEY_DEVICE_ID to deviceId,
                    ),
                ),
            ),
        ),
        actionId = BlockAction.id,
        actionParams = Params.EMPTY,
    )

    private fun decide(rule: Rule, context: EvalContext): Decision =
        engine.decide(listOf(rule), context) { emptySet() }

    // ---- 条件そのもの --------------------------------------------------

    @Test
    fun `向こうで効いていれば成立する`() {
        val decision = decide(linkedRule(), ctx(linked = { _, _ -> true }))
        assertTrue(decision is Decision.Act)
    }

    @Test
    fun `届いていなければ成立しない`() {
        // 圏外で塞がるより、圏外で緩むほうへ倒してある
        assertEquals(Decision.Allow, decide(linkedRule(), ctx()))
    }

    @Test
    fun `指す先を空にすると自分自身`() {
        // いちばん多い使い方。同じルールが端末をまたいで同じ uid を持つので、
        // 空にしておけば「どちらで使い切っても両方閉まる」になる
        var asked = ""
        decide(linkedRule(uid = ""), ctx(linked = { uid, _ -> asked = uid; false }))
        assertEquals("r-youtube", asked)
    }

    @Test
    fun `端末を指定すればその端末だけ見る`() {
        var askedDevice = "まだ"
        decide(linkedRule(deviceId = "pc"), ctx(linked = { _, device -> askedDevice = device; false }))
        assertEquals("pc", askedDevice)
    }

    @Test
    fun `連動が届かなくても手元の持ち時間は効く`() {
        // 上乗せであって土台ではない。ネットが死んでも手元の縛りは残る
        val exhausted = WindowUsage(usedSeconds = 130 * 60L, remainingSeconds = 50 * 60L)
        assertTrue(decide(linkedRule(), ctx(usage = exhausted)) is Decision.Act)
    }

    // ---- 配る値の読みかた ----------------------------------------------

    @Test
    fun `自分の端末の状態は読まない`() {
        // 同じルールが端末をまたいで同じ uid を持つ。自分を外さないと、
        // 自分の状態を自分で読んで一度成立したら永久に外れない
        val mine = RuleState("r-youtube", "phone", active = true, activeUntilSec = now + 600)
        assertFalse(RuleStates.isActive(listOf(mine), "r-youtube", now, myDeviceId = "phone"))
        assertTrue(RuleStates.isActive(listOf(mine), "r-youtube", now, myDeviceId = "pc"))
    }

    @Test
    fun `締め切りを過ぎたら緩む`() {
        // 向こうが黙ったまま塞がり続けるのがいちばん困る
        val stale = RuleState("r-youtube", "phone", active = true, activeUntilSec = now - 1)
        assertFalse(RuleStates.isActive(listOf(stale), "r-youtube", now, myDeviceId = "pc"))
    }

    @Test
    fun `効いていないという知らせは効かせない`() {
        val off = RuleState("r-youtube", "phone", active = false, activeUntilSec = now + 600)
        assertFalse(RuleStates.isActive(listOf(off), "r-youtube", now, myDeviceId = "pc"))
    }

    @Test
    fun `端末を指定すると他の端末は無視する`() {
        val onPhone = RuleState("r-youtube", "phone", active = true, activeUntilSec = now + 600)
        assertTrue(RuleStates.isActive(listOf(onPhone), "r-youtube", now, "pc", deviceId = "phone"))
        assertFalse(RuleStates.isActive(listOf(onPhone), "r-youtube", now, "pc", deviceId = "tablet"))
    }

    @Test
    fun `指す先が空なら何も効かない`() {
        val any = RuleState("r-youtube", "phone", active = true, activeUntilSec = now + 600)
        assertFalse(RuleStates.isActive(listOf(any), "", now, myDeviceId = "pc"))
    }

    // ---- 配るとき ------------------------------------------------------

    @Test
    fun `変わった瞬間だけ配る`() {
        val was = RuleStates.publish("r", "phone", active = true, nowSec = now)
        // 同じままなら配り直さない。毎回書くと何も起きていない日でも行が動く
        assertFalse(RuleStates.shouldPublish(was, active = true, nowSec = now))
        // 外れたら配る
        assertTrue(RuleStates.shouldPublish(was, active = false, nowSec = now))
        // 初めて効いたら配る
        assertTrue(RuleStates.shouldPublish(null, active = true, nowSec = now))
        // 初めから効いていなければ黙っていていい
        assertFalse(RuleStates.shouldPublish(null, active = false, nowSec = now))
    }

    @Test
    fun `締め切りが近づいたら配り直す`() {
        // 切れてから直すと一瞬緩む
        val was = RuleStates.publish("r", "phone", active = true, nowSec = now)
        val nearEnd = was.activeUntilSec - RuleStates.REFRESH_AFTER_MINUTES * 60L + 1
        assertTrue(RuleStates.shouldPublish(was, active = true, nowSec = nearEnd))
    }

    @Test
    fun `外すときは締め切りを延ばさない`() {
        val off = RuleStates.publish("r", "phone", active = false, nowSec = now)
        assertFalse(off.isLiveAt(now))
    }

    @Test
    fun `古い行は落とす`() {
        val old = RuleState("r", "phone", active = true, activeUntilSec = now - RuleStates.TTL_MINUTES * 60L - 1)
        val fresh = RuleState("r", "pc", active = true, activeUntilSec = now + 60)
        assertEquals(listOf(fresh), RuleStates.prune(listOf(old, fresh), now))
    }

    // ---- 足す・外す ----------------------------------------------------

    private val budget = ConditionNode.Leaf(
        WindowBudgetCondition.id,
        Params.of(
            WindowBudgetCondition.KEY_WINDOW_MINUTES to 180,
            WindowBudgetCondition.KEY_BUDGET_MINUTES to 120,
        ),
    )

    @Test
    fun `元の条件との OR で足す`() {
        // AND にすると、向こうで効いているときしか効かなくなり、
        // 1台で使っているあいだ何も起きない
        val linked = RuleLinks.withLink(budget)
        assertTrue(linked is ConditionNode.AnyOf)
        assertEquals(2, (linked as ConditionNode.AnyOf).children.size)
        assertTrue(RuleLinks.contains(linked))
    }

    @Test
    fun `二度足しても増えない`() {
        val once = RuleLinks.withLink(budget)
        assertEquals(once, RuleLinks.withLink(once))
    }

    @Test
    fun `すでに OR ならその中に足す`() {
        // 入れ子を無駄に深くしない
        val anyOf = ConditionNode.AnyOf(listOf(budget, budget))
        val linked = RuleLinks.withLink(anyOf) as ConditionNode.AnyOf
        assertEquals(3, linked.children.size)
    }

    @Test
    fun `外すと元に戻る`() {
        // かぶせた殻ごと剥がさないと、足して外すたびに殻が積もる
        assertEquals(budget, RuleLinks.withoutLink(RuleLinks.withLink(budget)))
    }

    @Test
    fun `入っていなければ外しても変わらない`() {
        assertEquals(budget, RuleLinks.withoutLink(budget))
    }

    @Test
    fun `見られているのは指されたルールだけ`() {
        val watched = Rule(
            id = 1, uid = "watched", name = "見られる側",
            target = youtube, condition = budget,
            actionId = BlockAction.id, actionParams = Params.EMPTY,
        )
        val watcher = Rule(
            id = 2, uid = "watcher", name = "見る側",
            target = youtube,
            condition = ConditionNode.Leaf(
                LinkedRuleCondition.id,
                Params.of(LinkedRuleCondition.KEY_RULE_UID to "watched"),
            ),
            actionId = BlockAction.id, actionParams = Params.EMPTY,
        )
        assertEquals(setOf("watched"), RuleLinks.watchedUids(listOf(watched, watcher)))
    }

    @Test
    fun `自分を指す条件は自分を見られている側にする`() {
        // いちばん多い使い方。空 = このルール自身
        val self = Rule(
            id = 1, uid = "self", name = "両方で効かせる",
            target = youtube, condition = RuleLinks.withLink(budget),
            actionId = BlockAction.id, actionParams = Params.EMPTY,
        )
        assertEquals(setOf("self"), RuleLinks.watchedUids(listOf(self)))
    }

    @Test
    fun `止めてあるルールは配らない`() {
        val off = Rule(
            id = 1, uid = "self", name = "止めてある", enabled = false,
            target = youtube, condition = RuleLinks.withLink(budget),
            actionId = BlockAction.id, actionParams = Params.EMPTY,
        )
        assertTrue(RuleLinks.watchedUids(listOf(off)).isEmpty())
    }

    @Test
    fun `鍵はルールと端末の組`() {
        // ルールだけを鍵にすると、端末どうしで上書き合戦になる
        assertEquals("r-youtube@phone", RuleState("r-youtube", "phone").uid)
    }
}
