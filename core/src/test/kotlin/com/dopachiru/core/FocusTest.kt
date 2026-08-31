package com.dopachiru.core

import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.Focus
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Lockouts
import com.dopachiru.core.model.Target
import com.dopachiru.core.time.ResetPolicy
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 自分で始める集中モード。 */
class FocusTest {

    private val engine = RuleEngine()
    private val noTags: (String) -> Set<String> = { emptySet() }
    private val now = 1_000_000L

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun ctx(pkg: String) = EvalContext(
        now = LocalDateTime.of(2026, 8, 31, 19, 0),
        packageName = pkg,
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = 0
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        },
    )

    // ---- 始める -----------------------------------------------------------

    @Test
    fun `始めると端末全体が閉まる`() {
        val focus = Focus.start(now, 15)
        assertTrue(focus.target.matches("com.example.sns", emptySet()))
        assertTrue(focus.target.matches("com.example.anything", emptySet()))
        assertEquals(now + 15 * 60, focus.untilEpochSec)
    }

    @Test
    fun `逃がしたアプリは開いたまま`() {
        val focus = Focus.start(now, 15, allowPackages = setOf("com.example.music"))
        assertFalse(focus.target.matches("com.example.music", emptySet()))
        assertTrue(focus.target.matches("com.example.sns", emptySet()))
    }

    @Test
    fun `長さは5分刻みに丸まる`() {
        assertEquals(15, Focus.clampMinutes(17))
        assertEquals(15, Focus.clampMinutes(19))
        assertEquals(20, Focus.clampMinutes(20))
        assertEquals(Focus.MIN_MINUTES, Focus.clampMinutes(1))
        assertEquals(Focus.MAX_MINUTES, Focus.clampMinutes(99_999))
    }

    @Test
    fun `毎回ちがう uid が振られる`() {
        // 同期で突き合わせる鍵。重なると片方が消える
        val a = Focus.start(now, 15)
        val b = Focus.start(now, 15)
        assertTrue(a.uid.isNotBlank())
        assertTrue(a.uid != b.uid)
    }

    // ---- 罰との違い -------------------------------------------------------

    @Test
    fun `自分で始めたものには出口があり罰には無い`() {
        assertTrue(Focus.start(now, 15).isChosen)

        val punishment = Lockout(
            target = Target(matchAll = true),
            untilEpochSec = now + 600,
            reason = "押し切った",
            createdAtEpochSec = now,
        )
        assertFalse(punishment.isChosen)
        assertNull(punishment.earlyExit)
    }

    @Test
    fun `押し間違いはしばらく無料で取り消せる`() {
        val focus = Focus.start(now, 15)
        assertTrue(focus.canCancelFreelyAt(now))
        assertTrue(focus.canCancelFreelyAt(now + Focus.FREE_CANCEL_SEC - 1))
        assertFalse(focus.canCancelFreelyAt(now + Focus.FREE_CANCEL_SEC))
    }

    // ---- 足す -------------------------------------------------------------

    @Test
    fun `時間を足せる`() {
        val focus = Focus.start(now, 15)
        val longer = Focus.extend(focus, 10, now + 60)
        assertEquals(focus.untilEpochSec + 10 * 60, longer.untilEpochSec)
    }

    @Test
    fun `足しても上限は越えない`() {
        val focus = Focus.start(now, Focus.MAX_MINUTES)
        val longer = Focus.extend(focus, 60, now + 60)
        assertEquals(focus.createdAtEpochSec + Focus.MAX_MINUTES * 60L, longer.untilEpochSec)
    }

    @Test
    fun `終わったものは足しても生き返らない`() {
        // 期限切れを延ばせると、忘れていた集中が突然また閉まる
        val focus = Focus.start(now, 15)
        val after = now + 16 * 60
        assertEquals(focus, Focus.extend(focus, 30, after))
    }

    // ---- エンジンの扱い ---------------------------------------------------

    @Test
    fun `集中中はルールを問わず閉まる`() {
        val focus = Focus.start(now, 15)
        val decision = engine.decide(
            rules = emptyList(),
            lockouts = listOf(focus),
            ctx = ctx("com.example.sns"),
            nowSec = now + 60,
            passUntilSec = 0L,
            tagsOf = noTags,
        )
        assertIs<Decision.Locked>(decision)
    }

    @Test
    fun `解禁券では集中は止まらない`() {
        // 集中も封鎖なので、罰と同じくポイントでは買い戻せない道を通る。
        // 途中でやめるのは earlyExit の側の話
        val focus = Focus.start(now, 15)
        val decision = engine.decide(
            rules = emptyList(),
            lockouts = listOf(focus),
            ctx = ctx("com.example.sns"),
            nowSec = now + 60,
            passUntilSec = now + 9_999,
            tagsOf = noTags,
        )
        assertIs<Decision.Locked>(decision)
    }

    @Test
    fun `時間が来れば勝手に解ける`() {
        val focus = Focus.start(now, 15)
        assertEquals(emptyList(), Lockouts.prune(listOf(focus), now + 15 * 60))
    }

    @Test
    fun `走っている集中を拾える`() {
        val punishment = Lockout(
            target = Target(matchAll = true),
            untilEpochSec = now + 9_999,
            reason = "押し切った",
            createdAtEpochSec = now,
        )
        val focus = Focus.start(now, 15)
        // 罰は混ざらない
        assertEquals(focus.uid, Focus.activeIn(listOf(punishment, focus), now + 60)?.uid)
        assertNull(Focus.activeIn(listOf(punishment), now + 60))
        assertNull(Focus.activeIn(listOf(focus), now + 16 * 60))
    }

    @Test
    fun `JSONで往復できる`() {
        // 同期に載せる前提なので、出口の条件まで含めて往復すること
        val focus = Focus.start(now, 15, allowPackages = setOf("com.example.music"), abortPoints = 10)
        val json = kotlinx.serialization.json.Json.encodeToString(Lockout.serializer(), focus)
        val back = kotlinx.serialization.json.Json.decodeFromString(Lockout.serializer(), json)
        assertEquals(focus, back)
        assertNotNull(back.earlyExit)
        assertEquals(10, back.earlyExit.points)
    }
}
