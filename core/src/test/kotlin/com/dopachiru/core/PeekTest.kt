package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.condition.types.AlwaysCondition
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Focus
import com.dopachiru.core.model.FocusSettings
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Peek
import com.dopachiru.core.model.PeekAllowance
import com.dopachiru.core.model.PeekCheck
import com.dopachiru.core.model.Peeks
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 集中とブロックの「◯分だけのぞく」。 */
class PeekTest {

    private val engine = RuleEngine()
    private val noTags: (String) -> Set<String> = { emptySet() }
    private val now = 1_000_000L
    private val sns = "com.example.sns"
    private val chat = "com.example.chat"
    private val allow = PeekAllowance(minutes = 5, maxCount = 3, gapMinutes = 30)

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun ctx(pkg: String) = EvalContext(
        now = LocalDateTime.of(2026, 10, 7, 19, 0),
        packageName = pkg,
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = 0
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        },
    )

    private fun peekAt(source: String, pkg: String, startSec: Long, minutes: Int = 5) =
        Peek(source, pkg, startSec, startSec + minutes * 60L)

    // ---- 回数と間隔 --------------------------------------------------------

    @Test
    fun `回数を使い切ったら断る`() {
        val log = listOf(
            peekAt("focus:a", sns, now - 7200),
            peekAt("focus:a", sns, now - 5400),
            peekAt("focus:a", sns, now - 3600),
        )
        assertIs<PeekCheck.Refused>(Peeks.check(allow, log, "focus:a", sns, sinceSec = 0, nowSec = now))
    }

    @Test
    fun `残りの回数を返す`() {
        val log = listOf(peekAt("focus:a", sns, now - 7200))
        val ok = assertIs<PeekCheck.Ok>(Peeks.check(allow, log, "focus:a", sns, 0, now))
        assertEquals(1, ok.remainingAfter)
    }

    @Test
    fun `前ののぞきが終わってから間隔をあける`() {
        // 10分前に始めて5分前に終わった。30分あけるので、あと25分
        val log = listOf(peekAt("focus:a", sns, now - 600))
        val refused = assertIs<PeekCheck.Refused>(Peeks.check(allow, log, "focus:a", sns, 0, now))
        assertEquals("次にのぞけるのは、あと25分たってから。", refused.reason)

        assertIs<PeekCheck.Ok>(Peeks.check(allow, log, "focus:a", sns, 0, now + 25 * 60))
    }

    @Test
    fun `別の集中や別のルールの回数は混ぜない`() {
        val log = List(3) { peekAt("focus:old", sns, now - 10_000 - it * 3000L) }
        assertIs<PeekCheck.Ok>(Peeks.check(allow, log, "focus:new", sns, 0, now))
    }

    @Test
    fun `区切りより前の回数は数えない`() {
        val log = List(3) { peekAt("rule:r", sns, now - 10_000 - it * 3000L) }
        assertIs<PeekCheck.Ok>(Peeks.check(allow, log, "rule:r", sns, sinceSec = now - 5000, nowSec = now))
    }

    @Test
    fun `対象ぜんぶで数えるとほかのアプリの分も減る`() {
        val log = List(3) { peekAt("focus:a", chat, now - 10_000 - it * 3000L) }
        assertIs<PeekCheck.Refused>(Peeks.check(allow, log, "focus:a", sns, 0, now))
        assertIs<PeekCheck.Ok>(Peeks.check(allow.copy(perApp = true), log, "focus:a", sns, 0, now))
    }

    @Test
    fun `アプリごとなら間隔もアプリごと`() {
        val log = listOf(peekAt("focus:a", chat, now - 600))
        assertIs<PeekCheck.Refused>(Peeks.check(allow, log, "focus:a", sns, 0, now))
        assertIs<PeekCheck.Ok>(Peeks.check(allow.copy(perApp = true), log, "focus:a", sns, 0, now))
    }

    // ---- 1日の区切り ------------------------------------------------------

    @Test
    fun `1日は朝4時で区切る`() {
        val zone = ZoneId.of("Asia/Tokyo")
        fun sec(h: Int, d: Int = 7) = LocalDateTime.of(2026, 10, d, h, 0).atZone(zone).toEpochSecond()

        assertEquals(sec(4), Peeks.dayStart(sec(23), zone))
        // 深夜2時はまだ前の日
        assertEquals(sec(4, d = 6), Peeks.dayStart(sec(2), zone))
        assertEquals(sec(4), Peeks.dayStart(sec(4), zone))
    }

    // ---- 集中 --------------------------------------------------------------

    @Test
    fun `のぞきを入れていなければ集中に写らない`() {
        assertNull(FocusSettings().peekForStart())
        val focus = Focus.start(now, 15, peek = FocusSettings().peekForStart())
        assertNull(focus.earlyExit?.peek)
    }

    @Test
    fun `集中は始めた時点ののぞきを持つ`() {
        val settings = FocusSettings(peekEnabled = true, peek = allow)
        val focus = Focus.start(now, 15, peek = settings.peekForStart())
        assertEquals(allow, focus.earlyExit?.peek)
    }

    @Test
    fun `のぞいているアプリだけ集中が開く`() {
        val focus = Focus.start(now, 30, peek = allow)
        val peeking = setOf(Peeks.focusSource(focus))

        val open = engine.decide(emptyList(), listOf(focus), ctx(sns), now, 0L, noTags, peeking)
        assertIs<Decision.Allow>(open)

        // ほかのアプリはのぞいていないので閉まったまま
        val other = engine.decide(emptyList(), listOf(focus), ctx(chat), now, 0L, noTags)
        assertIs<Decision.Locked>(other)
    }

    @Test
    fun `罰はのぞけない`() {
        val punishment = Lockout(
            uid = "p",
            target = Target(matchAll = true),
            untilEpochSec = now + 600,
            reason = "押し切った",
            createdAtEpochSec = now,
        )
        val decision = engine.decide(
            emptyList(), listOf(punishment), ctx(sns), now, 0L, noTags,
            setOf(Peeks.focusSource(punishment)),
        )
        assertIs<Decision.Locked>(decision)
    }

    @Test
    fun `のぞきを持たない集中は記録があっても開かない`() {
        val focus = Focus.start(now, 30)
        val decision = engine.decide(
            emptyList(), listOf(focus), ctx(sns), now, 0L, noTags,
            setOf(Peeks.focusSource(focus)),
        )
        assertIs<Decision.Locked>(decision)
    }

    // ---- ルール ------------------------------------------------------------

    private fun rule(uid: String, actionId: String = BlockAction.id, params: Params = Params.EMPTY) = Rule(
        uid = uid,
        name = uid,
        target = Target(packages = setOf(sns)),
        condition = ConditionNode.Leaf(AlwaysCondition.id, Params.EMPTY),
        actionId = actionId,
        actionParams = params,
    )

    @Test
    fun `のぞいているルールのブロックだけ外れる`() {
        val a = rule("a")
        val b = rule("b")
        val onlyA = setOf(Peeks.ruleSource(a))

        assertIs<Decision.Allow>(engine.decide(listOf(a), ctx(sns), noTags, onlyA))
        // 別のルールがまだ塞いでいる
        val both = assertIs<Decision.Act>(engine.decide(listOf(a, b), ctx(sns), noTags, onlyA))
        assertEquals("b", both.rule.uid)
    }

    @Test
    fun `のぞいてもブロック以外の措置は外れない`() {
        val delay = rule("a", actionId = DelayAction.id)
        val decision = engine.decide(listOf(delay), ctx(sns), noTags, setOf(Peeks.ruleSource(delay)))
        assertIs<Decision.Act>(decision)
    }

    @Test
    fun `ブロックののぞきは既定で切`() {
        assertNull(BlockAction.peekOf(Params.defaultsOf(BlockAction.params)))
    }

    @Test
    fun `ブロックののぞきを読む`() {
        val p = Params.defaultsOf(BlockAction.params).with(
            BlockAction.KEY_PEEK to true,
            BlockAction.KEY_PEEK_MINUTES to 3,
            BlockAction.KEY_PEEK_PER_APP to "true",
        )
        val peek = assertNotNull(BlockAction.peekOf(p))
        assertEquals(3, peek.minutes)
        assertEquals(3, peek.maxCount)
        assertEquals(30, peek.gapMinutes)
        assertEquals(true, peek.perApp)
    }
}
