package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Lockouts
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.time.ResetPolicy
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** URL でルールの対象を決められること。 */
class SiteTargetTest {

    private val engine = RuleEngine()
    private val noTags: (String) -> Set<String> = { emptySet() }

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun ctx(pkg: String = "chrome.exe", url: String? = null) = EvalContext(
        now = LocalDateTime.of(2026, 8, 17, 12, 0),
        packageName = pkg,
        url = url,
        usage = object : UsageSnapshot {
            override val currentSessionMinutes = 0
            override fun usageMinutesIn(policy: ResetPolicy) = 0
            override fun sessionCountIn(policy: ResetPolicy) = 0
        },
    )

    private fun siteRule(vararg sites: String) = Rule(
        id = 1L,
        name = "短い動画だけ止める",
        target = Target(sites = sites.toSet()),
        condition = ConditionNode.AllOf(emptyList()),
        actionId = BlockAction.id,
    )

    // ---- 対象の当たり判定 -----------------------------------------------

    @Test
    fun `サイト指定のルールはURLが当たれば発火する`() {
        val decision = engine.decide(
            listOf(siteRule("youtube.com/shorts")),
            ctx(url = "https://www.youtube.com/shorts/abc"),
            noTags,
        )
        assertIs<Decision.Act>(decision)
    }

    @Test
    fun `URLが取れないときサイト指定は当たらない`() {
        // ここが逆だと、ブラウザ以外のアプリまでサイト規則で巻き込む
        assertEquals(
            Decision.Allow,
            engine.decide(listOf(siteRule("youtube.com/shorts")), ctx(pkg = "notepad.exe"), noTags),
        )
    }

    @Test
    fun `同じサイトの別のページは通る`() {
        assertEquals(
            Decision.Allow,
            engine.decide(
                listOf(siteRule("youtube.com/shorts")),
                ctx(url = "https://www.youtube.com/watch?v=abc"),
                noTags,
            ),
        )
    }

    @Test
    fun `アプリ指定のルールはURLがあっても今まで通り効く`() {
        // 既存のルールが URL の有無で挙動を変えないこと
        val appRule = siteRule().copy(target = Target(packages = setOf("chrome.exe")))
        assertIs<Decision.Act>(
            engine.decide(listOf(appRule), ctx(url = "https://example.com/"), noTags),
        )
        assertIs<Decision.Act>(engine.decide(listOf(appRule), ctx(url = null), noTags))
    }

    // ---- 除外 -----------------------------------------------------------

    @Test
    fun `除外サイトは指定より強い`() {
        val target = Target(
            sites = setOf("youtube.com"),
            exceptSites = setOf("youtube.com/playlist"),
        )
        assertTrue(target.matches("chrome.exe", emptySet(), "https://youtube.com/watch?v=a"))
        assertFalse(target.matches("chrome.exe", emptySet(), "https://youtube.com/playlist?list=a"))
    }

    @Test
    fun `除外サイトはアプリ指定にも効く`() {
        // タグやパッケージで広く取って、特定のページだけ抜く書き方
        val target = Target(
            packages = setOf("chrome.exe"),
            exceptSites = setOf("docs.google.com"),
        )
        assertTrue(target.matches("chrome.exe", emptySet(), "https://youtube.com/"))
        assertFalse(target.matches("chrome.exe", emptySet(), "https://docs.google.com/document/d/1"))
    }

    @Test
    fun `全指定でも除外サイトは抜ける`() {
        val target = Target(matchAll = true, exceptSites = setOf("docs.google.com"))
        assertTrue(target.matches("chrome.exe", emptySet(), "https://youtube.com/"))
        assertFalse(target.matches("chrome.exe", emptySet(), "https://docs.google.com/a"))
    }

    // ---- 空判定 ---------------------------------------------------------

    @Test
    fun `サイトだけ指定したルールは空扱いにならない`() {
        // isEmpty が true だと保存前に弾かれる。アプリを選ばない雛形が作れなくなる
        assertFalse(Target(sites = setOf("tiktok.com")).isEmpty)
        assertTrue(Target().isEmpty)
    }

    // ---- 罰 -------------------------------------------------------------

    @Test
    fun `封鎖もサイトの範囲で効く`() {
        val lockouts = listOf(
            Lockout(
                id = 1L,
                target = Target(sites = setOf("tiktok.com")),
                untilEpochSec = 2_000L,
                reason = "押し切った",
                createdAtEpochSec = 1_000L,
            ),
        )
        assertEquals(
            1L,
            Lockouts.activeFor(lockouts, "chrome.exe", emptySet(), 1_500L, "https://tiktok.com/")?.id,
        )
        // 同じブラウザでも別のページは開く
        assertEquals(
            null,
            Lockouts.activeFor(lockouts, "chrome.exe", emptySet(), 1_500L, "https://example.com/"),
        )
    }

    @Test
    fun `解禁券より罰が先に見られるのはサイトでも同じ`() {
        val lockouts = listOf(
            Lockout(
                id = 9L,
                target = Target(sites = setOf("tiktok.com")),
                untilEpochSec = 2_000L,
                reason = "押し切った",
                createdAtEpochSec = 1_000L,
            ),
        )
        val decision = engine.decide(
            rules = emptyList(),
            lockouts = lockouts,
            ctx = ctx(url = "https://tiktok.com/foryou"),
            nowSec = 1_500L,
            passUntilSec = 9_999L,
            tagsOf = noTags,
        )
        assertIs<Decision.Locked>(decision)
    }
}
