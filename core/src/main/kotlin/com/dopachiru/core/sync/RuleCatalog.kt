package com.dopachiru.core.sync

import com.dopachiru.core.DopaCore
import com.dopachiru.core.model.ReservationPolicy
import com.dopachiru.core.model.ReservationRules
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.RuleLinks
import com.dopachiru.core.model.Target
import kotlinx.serialization.Serializable

/**
 * 1台ぶんのルールの名札。
 *
 * ルールそのものは配りません(各端末で直接作る)。それでもほかの端末のルールを
 * 指したい場面が2つあります ── **予約**(PC の枠をスマホから取る)と
 * **連動**(スマホで効いているあいだ PC でも効かせる)。どちらも条件の中身は要らず、
 * 名前と対象と予約の型があれば足りるので、それだけを配ります。
 *
 * 端末ごとに1件で、中身はまるごと置き換えます。ルール1本ずつにすると、
 * 消したルールのために墓標を持つ必要が出てきます。
 */
@Serializable
data class RuleCatalog(
    val deviceId: String,
    val rules: List<RuleCard> = emptyList(),
    /**
     * この端末のルールが連動で見ているルールの uid。
     *
     * 状態([RuleState])は見られているものしか配らないので、**持ち主は自分のルールが
     * 見られていることを知る必要があります。** これが無いと、スマホのルールを PC から
     * 指しても、スマホは誰も見ていないと思って状態を配りません。
     */
    val watching: Set<String> = emptySet(),
)

/** ルール1本ぶんの名札。 */
@Serializable
data class RuleCard(
    val uid: String,
    val name: String,
    val enabled: Boolean = true,
    val target: Target = Target(),
    /**
     * 予約で開くルールなら、その型。無ければ予約では開かない。
     *
     * **持ち主の端末で決めた数字をそのまま運びます。** 取る側で決め直せると、
     * スマホから PC の枠を取るときだけ条件が緩くなります。
     */
    val reservation: ReservationPolicy? = null,
)

object RuleCatalogs {

    /**
     * この端末のルールから名札を作る。
     *
     * @param policyOf 予約で開くルールの型。端末ごとに持ち方が違うので呼ぶ側が引く。
     */
    fun of(deviceId: String, rules: List<Rule>, policyOf: (Rule) -> ReservationPolicy): RuleCatalog =
        RuleCatalog(
            deviceId = deviceId,
            rules = rules
                .filter { it.uid.isNotBlank() && it.appliesToDevice(deviceId) }
                .map { rule ->
                    RuleCard(
                        uid = rule.uid,
                        name = rule.name,
                        enabled = rule.enabled,
                        target = rule.target,
                        reservation = if (ReservationRules.unlocksByReservation(rule)) policyOf(rule) else null,
                    )
                }
                // 並びを固定する。並びが揺れると中身が同じでも [contentKey] が変わる
                .sortedBy { it.uid },
            watching = RuleLinks.watchedUids(rules).toSortedSet(),
        )

    /**
     * 中身の指紋。**変わったときだけ更新時刻を進める**ために使う。
     *
     * 毎回いまの時刻で送ると、何も変えていない日でも同期のたびにサーバーの版数が進み、
     * ほかの端末が毎回同じものを受け取り直します。
     */
    fun contentKey(catalog: RuleCatalog): String =
        DopaCore.json.encodeToString(RuleCatalog.serializer(), catalog).hashCode().toUInt().toString(16)

    /** ほかの端末から見られている、この端末のルールの uid。 */
    fun watchedByOthers(catalogs: List<RuleCatalog>, myDeviceId: String): Set<String> =
        catalogs.filter { it.deviceId != myDeviceId }.flatMapTo(HashSet()) { it.watching }

    /** 端末とルールの uid から名札を引く。 */
    fun cardOf(catalogs: List<RuleCatalog>, deviceId: String, ruleUid: String): RuleCard? =
        catalogs.firstOrNull { it.deviceId == deviceId }?.rules?.firstOrNull { it.uid == ruleUid }
}
