package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 別の端末でそのルールが効いているあいだ成立する。
 *
 * ## 何を解くためのものか
 *
 * 使いすぎを止めるルールは、**端末を替えれば逃げられます。** スマホで
 * 「2時間使ったら1時間休憩」に当たっても、PC で開けば数え直し。持ち時間が
 * 端末ごとに1本ずつあるからで、ルールを配っても直りません ── 配られるのは
 * 決まりごとであって、使った時間ではないので。
 *
 * この条件は、**効いているという事実のほうを見ます。**
 *
 * ## いちばん素直な使い方は「自分自身を指す」
 *
 * 同じルールが端末をまたいで同じ [com.dopachiru.core.model.Rule.uid] を持つので、
 * 条件を
 *
 *     どれかが成立(使い始めてからの持ち時間 / 別の端末でこのルールが効いている)
 *
 * と書けば、**どちらの端末で使い切っても両方が閉まります。** ルールは1本のまま。
 *
 * 自分を指しても輪にならないのは、見るのが**自分以外の端末の状態**だけで、
 * 配るほうもこの条件を外して計算しているからです([com.dopachiru.core.sync.RuleStates])。
 *
 * ## ネットが要る唯一の条件です
 *
 * ほかの条件は全部その端末の中だけで決まりますが、これは同期で運ばれてきた
 * 状態を見ます。**届いていなければ成立しません** ── 圏外で塞がるより、
 * 圏外で緩むほうを選んでいます。締め切り切れも同じ扱い。
 *
 * つまりこれは**上乗せ**であって、土台にはできません。土台になる制限は、
 * その端末の中だけで完結する条件で書いてください。
 */
object LinkedRuleCondition : ConditionType {
    const val KEY_RULE_UID = "ruleUid"
    const val KEY_DEVICE_ID = "deviceId"

    override val id = "linked_active"
    override val displayName = "別の端末でこのルールが効いている"
    override val description =
        "指定したルールが、自分以外の端末でいま効いていれば成立する。" +
            "同じルールを指せば「どちらかで使い切ったら両方閉まる」になる。" +
            "同期が届いていなければ成立しない(上乗せであって、土台にはできない)。"

    override val group = ConditionGroup.TRIGGER
    override val example = "スマホで持ち時間を使い切っているあいだ、PC でも閉める"

    override val params = listOf(
        ParamSpec.RuleRefParam(
            KEY_RULE_UID,
            "どのルールを見るか",
            help = "空ならこのルール自身。同じルールを指すと「どちらで使い切っても両方閉まる」になります",
        ),
        ParamSpec.TextParam(
            KEY_DEVICE_ID,
            "どの端末を見るか",
            help = "空ならどの端末でも(自分以外)。ふつうは空のままで足ります",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        // 空なら自分自身を指す。いちばん多い使い方なので、選ばなくても通るようにする
        val uid = p.string(KEY_RULE_UID, "").ifBlank { ctx.currentRuleUid }
        if (uid.isBlank()) return false
        return ctx.linkedActiveOf(uid, p.string(KEY_DEVICE_ID, ""))
    }

    override fun summarize(p: Params): String {
        val device = p.string(KEY_DEVICE_ID, "")
        return if (device.isBlank()) {
            "別の端末で同じ縛りが効いている"
        } else {
            device + "でそのルールが効いている"
        }
    }

    // 次にいつ変わるかは**向こうの端末が決める**ので、こちらには分からない。
    // null を返すと呼ぶ側が短い間隔で見に来る。それでいい
    override fun nextChangeAt(p: Params, ctx: EvalContext) = null
}
