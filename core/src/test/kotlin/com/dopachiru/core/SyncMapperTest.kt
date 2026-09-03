package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.sync.AppInfo
import com.dopachiru.core.sync.Envelope
import com.dopachiru.core.sync.MergeAction
import com.dopachiru.core.sync.SyncMapper
import com.dopachiru.core.sync.decideMerge
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 手元のものと、線の上を流れる包みとの変換。 */
class SyncMapperTest {

    @Before
    fun setUp() = DopaCore.registerAll()

    private fun rule(uid: String = "u1", name: String = "夜はSNS", id: Long = 5L) = Rule(
        id = id,
        uid = uid,
        name = name,
        target = Target(packages = setOf("com.example.sns"), tags = setOf("sns")),
        condition = ConditionNode.AllOf(emptyList()),
        actionId = BlockAction.id,
        actionParams = Params.defaultsOf(BlockAction.params),
    )

    // ---- ルール -----------------------------------------------------------

    @Test
    fun `ルールが往復する`() {
        val back = SyncMapper.ruleOf(SyncMapper.ruleEnvelope(rule(), 100))
        assertNotNull(back)
        assertEquals("夜はSNS", back.name)
        assertEquals(setOf("com.example.sns"), back.target.packages)
        assertEquals(BlockAction.id, back.actionId)
    }

    @Test
    fun `端末ごとの番号は運ばない`() {
        // 向こうの番号をこちらに持ち込むと、同じ番号の既存ルールを踏む
        val envelope = SyncMapper.ruleEnvelope(rule(id = 42L), 100)
        assertTrue(!envelope.payload.toString().contains("\"id\":42"), envelope.payload.toString())
        assertEquals(0L, SyncMapper.ruleOf(envelope)?.id)
    }

    @Test
    fun `墓標は中身を運ばない`() {
        // 消したルールの中身まで送る必要は無いし、送ると消したものが線に残る
        val envelope = SyncMapper.ruleEnvelope(rule(), 100, deleted = true)
        assertTrue(envelope.deleted)
        assertEquals(0, envelope.payload.size)
        assertEquals("u1", envelope.uid)
    }

    @Test
    fun `読めない中身は null になって落ちない`() {
        assertNull(SyncMapper.ruleOf(Envelope("u1", 1, false)))
    }

    // ---- タグ -------------------------------------------------------------

    @Test
    fun `タグが往復する`() {
        val e = SyncMapper.tagsEnvelope("android", "com.example.sns", setOf("sns", "dopa"), 100)
        assertEquals("android:com.example.sns", e.uid)
        assertEquals(
            "com.example.sns" to setOf("sns", "dopa"),
            SyncMapper.tagsOf(e, "android"),
        )
    }

    @Test
    fun `別の端末のタグは受け取らない`() {
        // Windows の chrome.exe に付いたタグは、Android では使い道が無い
        val e = SyncMapper.tagsEnvelope("windows", "chrome.exe", setOf("sns"), 100)
        assertNull(SyncMapper.tagsOf(e, "android"))
        assertNotNull(SyncMapper.tagsOf(e, "windows"))
    }

    @Test
    fun `タグを全部外したことも伝わる`() {
        // タグごとに1件にすると「外した」を表せない。まとめて置き換える
        val e = SyncMapper.tagsEnvelope("android", "com.example.sns", emptySet(), 100)
        assertEquals("com.example.sns" to emptySet(), SyncMapper.tagsOf(e, "android"))
    }

    // ---- アプリの名札 -----------------------------------------------------

    @Test
    fun `名札が往復する`() {
        val info = AppInfo("com.twitter.android", "X", AppInfo.ANDROID)
        assertEquals(info, SyncMapper.appOf(SyncMapper.appEnvelope(info, 100)))
    }

    @Test
    fun `名札は別の端末のものでも受け取る`() {
        // 受け取らないと、PC の実績を chrome.exe としか出せない
        val info = AppInfo("chrome.exe", "Chrome", AppInfo.WINDOWS)
        assertEquals(info, SyncMapper.appOf(SyncMapper.appEnvelope(info, 100)))
    }

    @Test
    fun `名前が空の名札は捨てる`() {
        assertNull(SyncMapper.appOf(SyncMapper.appEnvelope(AppInfo("x", "", "android"), 1)))
    }

    // ---- 突き合わせ -------------------------------------------------------

    @Test
    fun `新しいほうが勝つ`() {
        assertEquals(MergeAction.Apply, decideMerge(Envelope("u1", 200), localUpdatedAt = 100))
        assertEquals(MergeAction.Skip, decideMerge(Envelope("u1", 100), localUpdatedAt = 200))
    }

    @Test
    fun `同値なら手元を残す`() {
        // 向こうを勝たせると、2台が同じ内容を延々と押し付け合う
        assertEquals(MergeAction.Skip, decideMerge(Envelope("u1", 100), localUpdatedAt = 100))
    }

    @Test
    fun `手元に無ければ入れる`() {
        assertEquals(MergeAction.Apply, decideMerge(Envelope("u1", 1), localUpdatedAt = null))
    }

    @Test
    fun `墓標が新しければ消す`() {
        assertEquals(
            MergeAction.Delete,
            decideMerge(Envelope("u1", 200, deleted = true), localUpdatedAt = 100),
        )
        // 古い墓標では消さない。消したあとに別の端末で作り直した場合を守る
        assertEquals(
            MergeAction.Skip,
            decideMerge(Envelope("u1", 50, deleted = true), localUpdatedAt = 100),
        )
    }
}
