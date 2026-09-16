package com.dopachiru.core

import com.dopachiru.core.condition.types.WindowBudgetCondition
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.engine.UsageWindows
import com.dopachiru.core.engine.WindowUsage
import com.dopachiru.core.param.Params
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「使い始めてから60分のうち15分まで」。
 *
 * いちばん大事なのは **ギリギリで閉じても数え直しにならない** こと。
 * ここが崩れると、この条件は [com.dopachiru.core.condition.types.UsageSinceBreakCondition]
 * と同じ穴を持つことになり、作った意味が無くなる。
 */
class WindowBudgetTest {

    private val now = 100_000L

    private fun min(m: Long) = m * 60

    /** いまから m 分前を始点、n 分前を終点とする区間。 */
    private fun span(fromMinutesAgo: Long, toMinutesAgo: Long) =
        (now - min(fromMinutesAgo)) to (now - min(toMinutesAgo))

    // ---- 窓の数え方 ----------------------------------------------------

    @Test
    fun `窓の中で使ったぶんを足す`() {
        // 30分前に使い始めて、10分使った
        val spans = listOf(span(30, 20))
        val usage = UsageWindows.current(spans, windowMinutes = 60, nowSec = now)
        assertEquals(10, usage.usedMinutes)
        assertTrue(usage.open)
        assertEquals(30, usage.remainingMinutes)
    }

    @Test
    fun `閉じても窓は消えない`() {
        // 50分前に使い始め、10分使って閉じた。40分空いているが、窓はまだ張られている
        val spans = listOf(span(50, 40))
        val usage = UsageWindows.current(spans, windowMinutes = 60, nowSec = now)
        assertEquals(10, usage.usedMinutes)
        assertTrue(usage.open)
    }

    @Test
    fun `ギリギリで閉じて開き直しても持ち時間は戻らない`() {
        // 14分使って閉じる → 少し空ける → また14分使う。
        // 「離れたら数え直す」なら 14 に戻るところが、こちらは合計28分のまま
        val spans = listOf(span(40, 26), span(20, 6))
        val usage = UsageWindows.current(spans, windowMinutes = 60, nowSec = now)
        assertEquals(28, usage.usedMinutes)
    }

    @Test
    fun `窓が明けたら持ち時間は満タンに戻る`() {
        // 90分前に使い始めた。60分の窓はとっくに明けていて、その後は触っていない
        val spans = listOf(span(90, 80))
        val usage = UsageWindows.current(spans, windowMinutes = 60, nowSec = now)
        assertFalse(usage.open)
        assertEquals(0, usage.usedMinutes)
    }

    @Test
    fun `窓が明けたあと触ったら、そこに新しい窓を張る`() {
        // 前の窓(90分前〜30分前)は明けている。20分前に触り直したので、そこが新しい起点
        val spans = listOf(span(90, 80), span(20, 10))
        val usage = UsageWindows.current(spans, windowMinutes = 60, nowSec = now)
        assertEquals(10, usage.usedMinutes)
        assertEquals(40, usage.remainingMinutes)
    }

    @Test
    fun `開いたまま窓が明けたら、明けた時点から張り直す`() {
        // 100分前から今まで開きっぱなし。張りっぱなしにすると持ち時間が無限になるので、
        // 60分の窓が明けた時点(40分前)から数え直す
        val spans = listOf(span(100, 0))
        val usage = UsageWindows.current(spans, windowMinutes = 60, nowSec = now)
        assertTrue(usage.open)
        assertEquals(40, usage.usedMinutes)
        assertEquals(20, usage.remainingMinutes)
    }

    @Test
    fun `渡り歩いても窓は1つ`() {
        // X を10分 → YouTube を10分。対象をまとめて渡す前提なので、窓も持ち時間も1つ
        val spans = listOf(span(30, 20), span(20, 10))
        assertEquals(20, UsageWindows.current(spans, windowMinutes = 60, nowSec = now).usedMinutes)
    }

    @Test
    fun `記録が無ければ窓は無い`() {
        assertFalse(UsageWindows.current(emptyList(), windowMinutes = 60, nowSec = now).open)
    }

    @Test
    fun `残りは切り上げる`() {
        // 残り30秒を「あと0分」と出すと、開かないのに開くように見える
        val usage = WindowUsage(usedSeconds = 0, remainingSeconds = 30)
        assertEquals(1, usage.remainingMinutes)
    }

    // ---- 条件 ----------------------------------------------------------

    private val params = Params.of(
        WindowBudgetCondition.KEY_WINDOW_MINUTES to 60,
        WindowBudgetCondition.KEY_BUDGET_MINUTES to 15,
    )

    private fun ctx(usage: WindowUsage) = EvalContext(
        now = LocalDateTime.of(2026, 9, 16, 21, 0),
        packageName = "com.google.android.youtube",
        usage = UsageSnapshot.EMPTY,
        currentRuleId = 3L,
        windowUsageOf = { ruleId, _ -> if (ruleId == 3L) usage else WindowUsage.NONE },
    )

    @Test
    fun `使い切るまでは成立しない`() {
        assertFalse(WindowBudgetCondition.evaluate(params, ctx(WindowUsage(min(14), min(46)))))
        assertTrue(WindowBudgetCondition.evaluate(params, ctx(WindowUsage(min(15), min(45)))))
    }

    @Test
    fun `使い切ったあとは窓が明けるまで成立したまま`() {
        // 残り時間を塞ぎ続けるための肝。ここが false に落ちると開き直せてしまう
        assertTrue(WindowBudgetCondition.evaluate(params, ctx(WindowUsage(min(15), min(1)))))
    }

    @Test
    fun `窓が明けたら成立しない`() {
        assertFalse(WindowBudgetCondition.evaluate(params, ctx(WindowUsage.NONE)))
    }

    @Test
    fun `数えられない端末では成立しない`() {
        val bare = EvalContext(
            now = LocalDateTime.of(2026, 9, 16, 21, 0),
            packageName = "com.x",
            usage = UsageSnapshot.EMPTY,
        )
        assertFalse(WindowBudgetCondition.evaluate(params, bare))
    }

    @Test
    fun `窓が無ければ持ち時間ぶん眠る`() {
        val at = WindowBudgetCondition.nextChangeAt(params, ctx(WindowUsage.NONE))
        assertEquals(LocalDateTime.of(2026, 9, 16, 21, 15), at)
    }

    @Test
    fun `使い切ったあとは窓が明けるまで眠る`() {
        val at = WindowBudgetCondition.nextChangeAt(params, ctx(WindowUsage(min(20), min(12))))
        assertEquals(LocalDateTime.of(2026, 9, 16, 21, 12), at)
    }

    @Test
    fun `使い切る前は、残りと窓尻の早いほうで起きる`() {
        // 持ち時間は残り10分だが、窓は3分後に明ける。早いほうを採る
        val at = WindowBudgetCondition.nextChangeAt(params, ctx(WindowUsage(min(5), min(3))))
        assertEquals(LocalDateTime.of(2026, 9, 16, 21, 3), at)
    }
}
