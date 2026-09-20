package com.dopachiru.core.sync

import kotlinx.serialization.Serializable

/**
 * 「そのルールが、その端末で、いま効いているか」。
 *
 * ## 何のためにあるか
 *
 * 使いすぎを止めるルールは、**端末を替えれば逃げられます。** スマホで
 * 「2時間使ったら1時間休憩」に当たっても、PC で同じアプリを開けば数え直し。
 * 持ち時間が端末ごとに1本ずつあるのだから当然で、ルールを配っても直りません
 * ── 配られるのは決まりごとであって、使った時間ではないので。
 *
 * そこで**効いているという事実のほうを配ります。** スマホで効いているあいだ、
 * PC 側のルールも成立させる([com.dopachiru.core.condition.types.LinkedRuleCondition])。
 *
 * ## 締め切りを持たせる理由
 *
 * 真偽値だけを配ると、**配った端末が黙った瞬間に固まります。** スマホを
 * 鞄にしまえば「効いている」と言ったきり更新が来ず、PC は永久に塞がったまま。
 * [activeUntilSec] を過ぎたものは古いものとして無視し、配る側が生きているあいだ
 * 更新し続ける形にしてあります。**黙ったら緩む**、が安全側です。
 *
 * ## 自分の手柄だけを配ること
 *
 * 配る値を計算するときは、**この仕組みそのものを外して**評価します
 * (相手が効いているから自分も効いている、を配らない)。でないと、
 * A が B を見て、B が A を見て、どちらも外れなくなります。
 */
@Serializable
data class RuleState(
    /** どのルールか。端末をまたいで同じ([com.dopachiru.core.model.Rule.uid])。 */
    val ruleUid: String,
    /** どの端末での話か。 */
    val deviceId: String,
    val active: Boolean = false,
    /** この時刻(エポック秒)を過ぎたら古いものとして無視する。 */
    val activeUntilSec: Long = 0L,
) {
    fun isLiveAt(nowSec: Long): Boolean = active && nowSec < activeUntilSec

    /** 同期の鍵。ルールと端末の組で1行。 */
    val uid: String get() = ruleUid + "@" + deviceId
}

object RuleStates {

    /**
     * 配った値が生きている長さ(分)。
     *
     * 配る側は同期のたびに延長します。長くすると、端末が落ちたときに
     * 塞がったままの時間が延びる。短くすると、同期が間に合わずにちらつく。
     * スマホ側の同期が5分おきなので、その数倍を取ってあります。
     */
    const val TTL_MINUTES = 30

    /** 締め切りが近づいたら配り直す。**切れてから直すと一瞬緩む。** */
    const val REFRESH_AFTER_MINUTES = 10

    /**
     * そのルールが、**自分以外のどこかの端末**で効いているか。
     *
     * 自分を外すのが要。同じルールが端末をまたいで同じ uid を持つので、
     * 外さないと自分の状態を自分で読んで、一度成立したら永久に外れません。
     *
     * @param deviceId 特定の端末に絞るなら渡す。空ならどの端末でも。
     */
    fun isActive(
        states: List<RuleState>,
        ruleUid: String,
        nowSec: Long,
        myDeviceId: String,
        deviceId: String = "",
    ): Boolean {
        if (ruleUid.isBlank()) return false
        return states.any {
            it.ruleUid == ruleUid &&
                it.deviceId != myDeviceId &&
                (deviceId.isBlank() || it.deviceId == deviceId) &&
                it.isLiveAt(nowSec)
        }
    }

    /** 古くなった行を落とす。ためておく意味が無いので。 */
    fun prune(states: List<RuleState>, nowSec: Long): List<RuleState> =
        states.filter { it.activeUntilSec > nowSec - TTL_MINUTES * 60L }

    /**
     * 配り直すべきか。
     *
     * **変わった瞬間と、締め切りが近づいたとき**だけ書きます。毎回書くと、
     * 何も起きていない日でも同期のたびに行が動きます。
     */
    fun shouldPublish(previous: RuleState?, active: Boolean, nowSec: Long): Boolean {
        if (previous == null) return active
        if (previous.active != active) return true
        if (!active) return false
        return previous.activeUntilSec - nowSec < REFRESH_AFTER_MINUTES * 60L
    }

    /** いま配る値。 */
    fun publish(ruleUid: String, deviceId: String, active: Boolean, nowSec: Long): RuleState =
        RuleState(
            ruleUid = ruleUid,
            deviceId = deviceId,
            active = active,
            activeUntilSec = if (active) nowSec + TTL_MINUTES * 60L else nowSec,
        )
}
