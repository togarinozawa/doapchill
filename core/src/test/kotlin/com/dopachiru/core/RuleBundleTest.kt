package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.io.RuleBundle
import com.dopachiru.core.io.RuleBundleIo
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ルールの持ち出しと取り込み。
 *
 * このファイルは**外の道具に書かせる**ことが前提なので、
 * 「きれいに書かれたもの」より「雑に書かれたもの」を通せるかを重く見る。
 */
class RuleBundleTest {

    @Before
    fun setUp() {
        DopaCore.registerAll()
    }

    private fun rule(
        name: String = "夜は開かない",
        uid: String = "uid-1",
        id: Long = 1L,
    ) = Rule(
        id = id,
        uid = uid,
        name = name,
        target = Target(packages = setOf("com.example.sns")),
        condition = ConditionNode.Leaf(
            TimeRangeCondition.id,
            Params.of(TimeRangeCondition.KEY_START to 1380, TimeRangeCondition.KEY_END to 360),
        ),
        actionId = BlockAction.id,
        actionParams = Params.defaultsOf(BlockAction.params),
    )

    private fun parse(text: String): RuleBundle {
        val result = RuleBundleIo.parse(text)
        assertIs<RuleBundleIo.ParseResult.Ok>(result, "読めなかった: $result")
        return result.bundle
    }

    // ---- 往復 -------------------------------------------------------------

    @Test
    fun `書き出したものを読み戻せる`() {
        val rules = listOf(rule(), rule(name = "昼も開かない", uid = "uid-2", id = 2L))
        val bundle = parse(RuleBundleIo.export(rules))
        assertEquals(rules.map { it.name }, bundle.rules.map { it.name })
        assertEquals(rules.map { it.uid }, bundle.rules.map { it.uid })
    }

    @Test
    fun `uid が空のまま書き出さない`() {
        // 空のまま出すと、取り込むたびに新しいルールとして増え続ける
        val bundle = parse(RuleBundleIo.export(listOf(rule(uid = ""))))
        assertTrue(bundle.rules.single().uid.isNotBlank())
    }

    @Test
    fun `タグも一緒に運ぶ`() {
        val tags = mapOf("com.example.sns" to setOf("sns", "dopa"))
        assertEquals(tags, parse(RuleBundleIo.export(listOf(rule()), tags)).tags)
    }

    // ---- 目録 -------------------------------------------------------------

    @Test
    fun `目録に登録済みの条件と措置が載る`() {
        // ここが欠けると、外の道具は当てずっぽうで書くしかなくなる
        val catalog = parse(RuleBundleIo.export(listOf(rule()))).catalog!!
        assertTrue(catalog.conditions.any { it.id == TimeRangeCondition.id })
        assertTrue(catalog.actions.any { it.id == BlockAction.id })
        assertTrue(catalog.siteGroups.any { it.id == "short_video" })
        assertTrue(catalog.hints.isNotEmpty(), "書き方の手引きが無い")
    }

    @Test
    fun `目録のパラメータに既定値と範囲が入る`() {
        val catalog = RuleBundleIo.buildCatalog()
        val timeRange = catalog.conditions.first { it.id == TimeRangeCondition.id }
        val start = timeRange.params.first { it.key == TimeRangeCondition.KEY_START }
        assertEquals("timeOfDay", start.type)
        assertTrue(start.range.isNotBlank(), "範囲が空だと何を書けばよいか分からない")
    }

    @Test
    fun `凍結中の条件は目録に出さない`() {
        // 選べないものを勧めると、書いたルールが取り込みで弾かれる
        val ids = RuleBundleIo.buildCatalog().conditions.map { it.id }
        assertFalse(ids.contains("calendar_busy"), "凍結中の条件が載っている")
    }

    // ---- 取り込みの計画 ---------------------------------------------------

    @Test
    fun `uid が同じルールは差し替えになる`() {
        val existing = listOf(rule(name = "むかしの名前", id = 7L))
        val bundle = parse(RuleBundleIo.export(listOf(rule(name = "あたらしい名前"))))
        val plan = RuleBundleIo.plan(bundle, existing)

        assertEquals(0, plan.added.size)
        assertEquals(1, plan.replaced.size)
        assertEquals("あたらしい名前", plan.replaced.single().second.name)
        // 番号は既存のものを保つ。変わると罰や記録の紐付けが切れる
        assertEquals(7L, plan.replaced.single().second.id)
    }

    @Test
    fun `uid が違えば増える`() {
        val bundle = parse(RuleBundleIo.export(listOf(rule(uid = "uid-新"))))
        val plan = RuleBundleIo.plan(bundle, listOf(rule(uid = "uid-旧")))
        assertEquals(1, plan.added.size)
        assertEquals(0, plan.replaced.size)
        // 増えるほうの番号は端末側で振り直す
        assertEquals(0L, plan.added.single().id)
    }

    // ---- 雑に書かれたものを通す -------------------------------------------

    @Test
    fun `足りないパラメータは既定値で埋まる`() {
        // 外の道具は全部の欄を埋めてこない。抜けを理由に丸ごと捨てない
        val text = """
            {
              "format": "dopachiru.rules",
              "rules": [{
                "name": "手で書いたルール",
                "target": { "sites": ["youtube.com/shorts"] },
                "condition": { "type": "leaf", "typeId": "time_range", "params": { "start": 1380 } },
                "actionId": "block"
              }]
            }
        """.trimIndent()
        val plan = RuleBundleIo.plan(parse(text), emptyList())
        assertEquals(0, plan.problems.size, plan.problems.toString())

        val added = plan.added.single()
        val leaf = added.condition as ConditionNode.Leaf
        // 書いたものは残る
        assertEquals(1380, leaf.params.int(TimeRangeCondition.KEY_START))
        // 書かなかったものは既定値で入る
        assertEquals(
            Params.defaultsOf(TimeRangeCondition.params).int(TimeRangeCondition.KEY_END),
            leaf.params.int(TimeRangeCondition.KEY_END),
        )
        // 措置の側も同じ
        assertTrue(added.actionParams.json.containsKey(BlockAction.KEY_MIN_SECONDS))
    }

    @Test
    fun `uid を書かなければ新しいルールとして増える`() {
        val text = """
            {"format":"dopachiru.rules","rules":[{
              "name":"uid なし","target":{"sites":["tiktok.com"]},
              "condition":{"type":"allOf","children":[]},"actionId":"warn"}]}
        """.trimIndent()
        val plan = RuleBundleIo.plan(parse(text), emptyList())
        assertEquals(1, plan.added.size)
        assertTrue(plan.added.single().uid.isNotBlank(), "uid が振られていない")
    }

    // ---- おかしなものを弾く -----------------------------------------------

    @Test
    fun `知らない条件は理由つきで弾く`() {
        val text = """
            {"format":"dopachiru.rules","rules":[{
              "name":"未来のルール","target":{"packages":["a"]},
              "condition":{"type":"leaf","typeId":"moon_phase","params":{}},"actionId":"block"}]}
        """.trimIndent()
        val plan = RuleBundleIo.plan(parse(text), emptyList())
        assertTrue(plan.isEmpty)
        assertTrue(plan.problems.single().reason.contains("moon_phase"), plan.problems.toString())
    }

    @Test
    fun `知らない措置は理由つきで弾く`() {
        val text = """
            {"format":"dopachiru.rules","rules":[{
              "name":"未来の措置","target":{"packages":["a"]},
              "condition":{"type":"allOf","children":[]},"actionId":"electrocute"}]}
        """.trimIndent()
        val plan = RuleBundleIo.plan(parse(text), emptyList())
        assertTrue(plan.problems.single().reason.contains("electrocute"))
    }

    @Test
    fun `対象が空のルールは弾く`() {
        // 何にも当たらないルールは、入っていても効かない。黙って入れるほうが害
        val text = """
            {"format":"dopachiru.rules","rules":[{
              "name":"対象なし","target":{},
              "condition":{"type":"allOf","children":[]},"actionId":"block"}]}
        """.trimIndent()
        assertTrue(RuleBundleIo.plan(parse(text), emptyList()).problems.isNotEmpty())
    }

    @Test
    fun `URL の書き間違いは弾く`() {
        val text = """
            {"format":"dopachiru.rules","rules":[{
              "name":"打ち間違い","target":{"sites":["youtube"]},
              "condition":{"type":"allOf","children":[]},"actionId":"block"}]}
        """.trimIndent()
        val plan = RuleBundleIo.plan(parse(text), emptyList())
        assertTrue(plan.problems.single().reason.contains("youtube"), plan.problems.toString())
    }

    @Test
    fun `1件おかしくても残りは取り込む`() {
        val text = """
            {"format":"dopachiru.rules","rules":[
              {"name":"だめなほう","target":{"packages":["a"]},
               "condition":{"type":"leaf","typeId":"moon_phase","params":{}},"actionId":"block"},
              {"name":"よいほう","target":{"sites":["tiktok.com"]},
               "condition":{"type":"allOf","children":[]},"actionId":"warn"}]}
        """.trimIndent()
        val plan = RuleBundleIo.plan(parse(text), emptyList())
        assertEquals(1, plan.added.size)
        assertEquals(1, plan.problems.size)
        assertEquals("よいほう", plan.added.single().name)
    }

    // ---- ファイルそのものがおかしい ---------------------------------------

    @Test
    fun `別のファイルを渡されたら断る`() {
        val r = RuleBundleIo.parse("""{"format":"something.else","rules":[]}""")
        assertIs<RuleBundleIo.ParseResult.Failed>(r)
    }

    @Test
    fun `JSON でなければ断る`() {
        assertIs<RuleBundleIo.ParseResult.Failed>(RuleBundleIo.parse("これはJSONではない"))
        assertIs<RuleBundleIo.ParseResult.Failed>(RuleBundleIo.parse(""))
    }

    @Test
    fun `新しすぎる形式は断る`() {
        val r = RuleBundleIo.parse("""{"format":"dopachiru.rules","version":99,"rules":[]}""")
        assertIs<RuleBundleIo.ParseResult.Failed>(r)
        assertTrue(r.message.contains("更新"), r.message)
    }

    @Test
    fun `知らない欄があっても読む`() {
        // 外の道具が余計な欄を足しても落ちないこと
        val r = RuleBundleIo.parse(
            """{"format":"dopachiru.rules","rules":[],"somethingNew":123}""",
        )
        assertIs<RuleBundleIo.ParseResult.Ok>(r)
    }

    @Test
    fun `要約は何が起きるかを言う`() {
        val bundle = parse(RuleBundleIo.export(listOf(rule(uid = "a"), rule(uid = "b", id = 2L))))
        val plan = RuleBundleIo.plan(bundle, listOf(rule(uid = "a", id = 9L)))
        val summary = plan.summary()
        assertTrue(summary.contains("1件追加"), summary)
        assertTrue(summary.contains("1件差し替え"), summary)
    }

    @Test
    fun `措置の付随する報いも運ばれる`() {
        val strict = rule().copy(
            consequence = com.dopachiru.core.model.Consequence(
                lockScope = com.dopachiru.core.model.LockScope.RULE_TARGET,
                lockMinutes = 30,
                lockEscalates = true,
            ),
        )
        val bundle = parse(RuleBundleIo.export(listOf(strict)))
        val back = bundle.rules.single().consequence
        assertEquals(30, back.lockMinutes)
        assertTrue(back.lockEscalates)
    }

    @Test
    fun `警告のルールも往復する`() {
        val warn = rule().copy(
            actionId = WarnAction.id,
            actionParams = Params.defaultsOf(WarnAction.params),
        )
        val plan = RuleBundleIo.plan(parse(RuleBundleIo.export(listOf(warn))), emptyList())
        assertEquals(WarnAction.id, plan.added.single().actionId)
    }
}
