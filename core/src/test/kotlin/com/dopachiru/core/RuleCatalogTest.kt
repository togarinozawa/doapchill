package com.dopachiru.core

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.types.LinkedRuleCondition
import com.dopachiru.core.condition.types.ReservationCondition
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.ReservationRules
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.RuleLinks
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.sync.RuleCatalog
import com.dopachiru.core.sync.RuleCatalogs
import com.dopachiru.core.sync.SyncMapper
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 端末ごとのルールの名札。予約と連動でほかの端末のルールを指すためのもの。 */
class RuleCatalogTest {

    @Before
    fun setUp() = DopaCore.registerAll()

    private fun rule(uid: String, name: String, condition: ConditionNode = ConditionNode.AllOf()) = Rule(
        uid = uid,
        name = name,
        target = Target(packages = setOf("steam.exe")),
        condition = condition,
        actionId = BlockAction.id,
        actionParams = Params.defaultsOf(BlockAction.params),
    )

    private val reservable = ConditionNode.Leaf(ReservationCondition.id, Params.EMPTY)

    @Test
    fun `予約で開くルールだけが型を持つ`() {
        val catalog = RuleCatalogs.of(
            "pc",
            listOf(rule("a", "ゲームは予約だけ", reservable), rule("b", "夜は閉める")),
        ) { ReservationRules.defaultFor(it).copy(maxDurationMinutes = 90) }

        val game = catalog.rules.first { it.uid == "a" }
        assertEquals(90, game.reservation?.maxDurationMinutes)
        assertNull(catalog.rules.first { it.uid == "b" }.reservation)
    }

    @Test
    fun `uid の無いルールは載せない`() {
        // 指せないものを載せても、選んだあとに行き先が無い
        val catalog = RuleCatalogs.of("pc", listOf(rule("", "むかしのルール"))) { ReservationRules.defaultFor(it) }
        assertTrue(catalog.rules.isEmpty())
    }

    @Test
    fun `指紋は中身が同じなら同じで、変われば変わる`() {
        val rules = listOf(rule("a", "一"), rule("b", "二"))
        val one = RuleCatalogs.of("pc", rules) { ReservationRules.defaultFor(it) }
        // 並びが違っても同じ指紋。並びで揺れると、何も変えていないのに送り直す
        val two = RuleCatalogs.of("pc", rules.reversed()) { ReservationRules.defaultFor(it) }
        assertEquals(RuleCatalogs.contentKey(one), RuleCatalogs.contentKey(two))

        val renamed = RuleCatalogs.of("pc", listOf(rule("a", "一"), rule("b", "三"))) {
            ReservationRules.defaultFor(it)
        }
        assertNotEquals(RuleCatalogs.contentKey(one), RuleCatalogs.contentKey(renamed))
    }

    @Test
    fun `名札が往復する`() {
        val catalog = RuleCatalogs.of("pc", listOf(rule("a", "ゲームは予約だけ", reservable))) {
            ReservationRules.defaultFor(it)
        }
        val back = SyncMapper.catalogOf(SyncMapper.catalogEnvelope(catalog, 100))
        assertEquals(catalog, back)
    }

    @Test
    fun `連動で見ている相手を名札に載せる`() {
        // スマホのルールを PC から指したとき、スマホがそれを知って状態を配るため
        val linked = rule(
            "p1",
            "スマホの持ち時間",
            RuleLinks.linkTo(ConditionNode.AllOf(), ruleUid = "x9", deviceId = "phone"),
        )
        val pc = RuleCatalogs.of("pc", listOf(linked)) { ReservationRules.defaultFor(it) }
        assertTrue("x9" in pc.watching)

        val phone = RuleCatalog("phone", watching = setOf("zz"))
        assertEquals(setOf("x9"), RuleCatalogs.watchedByOthers(listOf(pc, phone), myDeviceId = "phone"))
    }

    // ---- 連動 -------------------------------------------------------------

    @Test
    fun `名指しの連動は元の条件とORで足し、付け替えられる`() {
        val night = ConditionNode.Leaf(TimeRangeCondition.id, Params.EMPTY)
        val once = RuleLinks.linkTo(night, "x1", "pc")
        assertEquals("x1" to "pc", RuleLinks.linkOf(once))
        assertTrue(once is ConditionNode.AnyOf)

        // 付け替えても連動は1つのまま。積もると2台ぶん見に行く
        val again = RuleLinks.linkTo(once, "x2", "tablet")
        assertEquals("x2" to "tablet", RuleLinks.linkOf(again))
        val links = (again as ConditionNode.AnyOf).children.count {
            it is ConditionNode.Leaf && it.typeId == LinkedRuleCondition.id
        }
        assertEquals(1, links)

        // 外せば元どおり
        assertEquals(night, RuleLinks.withoutLink(again))
    }

    @Test
    fun `連動が無ければ null`() {
        assertNull(RuleLinks.linkOf(ConditionNode.AllOf()))
    }

    // ---- 予約の数えかた ---------------------------------------------------

    @Test
    fun `ほかの端末の枠は間隔と回数に数えない`() {
        // 予約は全端末に配られる。絞らないと PC の枠がスマホの「1日1回」を食う
        val policy = ReservationRules.defaultFor(rule("a", "ゲーム", reservable))
        val pcSlot = Reservation(
            uid = "r1",
            target = policy.target,
            startEpochSec = 10_000,
            endEpochSec = 13_600,
            policyId = policy.id,
            devices = setOf("pc"),
        )
        assertTrue(ReservationRules.bookedUnder(policy, listOf(pcSlot), 0, deviceId = "phone").isEmpty())
        assertNotNull(ReservationRules.bookedUnder(policy, listOf(pcSlot), 0, deviceId = "pc").singleOrNull())
        // 端末を指定しない古い枠はどこでも数える
        val everywhere = pcSlot.copy(uid = "r2", devices = emptySet())
        assertEquals(1, ReservationRules.bookedUnder(policy, listOf(everywhere), 0, deviceId = "phone").size)
    }
}
