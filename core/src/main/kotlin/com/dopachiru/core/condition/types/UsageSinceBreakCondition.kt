package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/**
 * 休憩をはさむまでの使用時間が閾値を超えたら成立する。
 *
 * 既にある2つの数え方の、ちょうど間を埋める:
 *  - [ContinuousUsageCondition] は**離れた瞬間に0に戻る**。
 *    ホームに1秒出て戻るだけで避けられる。
 *  - [TotalUsageCondition] は**期間が終わるまで戻らない**。
 *    一度超えたら、その日はずっと超えたまま。
 *
 * こちらは「[KEY_BREAK_MINUTES] 分きちんと離れたら数え直す」。
 * だから [com.dopachiru.core.action.types.LockoutAction] と組むと
 * 「n分使ったらm分閉め出す」が繰り返し成立する ── 閉め出されているあいだが
 * そのまま休憩になり、明ければまた0から数え始める。
 *
 * ## 対象ぜんぶをまとめて数える
 * ルールの対象がタグで括ってあれば、**グループ合計**で数える。
 * アプリごとに数えると、X で20分・YouTube で20分・TikTok で20分と
 * 渡り歩くだけで1時間使えてしまい、「SNSは20分まで」が意味を失う。
 */
object UsageSinceBreakCondition : ConditionType {
    const val KEY_MINUTES = "minutes"
    const val KEY_BREAK_MINUTES = "breakMinutes"

    override val id = "usage_since_break"
    override val displayName = "休憩をはさむまでの使用時間"
    override val description =
        "対象をまとめて何分使ったかを見る。しばらく離れたら数え直す。" +
            "「20分使ったら10分休む」を作るための条件。"

    override val group = ConditionGroup.USAGE
    /**
     * 新しくは選べません。[UsageBudgetCondition] にまとまりました。
     *
     * 実装を残してあるのは、**まだ移行していない保存を読むため**です。
     * 消すと、読み込みの途中で落ちた端末のルールが「知らない条件」になって
     * 成立しなくなります(= 黙って縛りが外れる)。
     */
    override val available = false
    override val example = "まとめて20分使ったら。10分離れれば数え直し"

    override val params = listOf(
        ParamSpec.DurationParam(
            KEY_MINUTES,
            "まとめて使った時間が",
            default = 20,
            min = 1,
            max = 12 * 60,
            help = "アプリを渡り歩いても切れない。タグで括ってあればグループの合計",
        ),
        ParamSpec.DurationParam(
            KEY_BREAK_MINUTES,
            "これだけ離れたら数え直す",
            default = 10,
            min = 1,
            max = 12 * 60,
            help = "閉め出す長さと同じにしておくのが素直。長くすると、明けた直後にまた閉まる",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean =
        used(p, ctx) >= p.int(KEY_MINUTES, 20)

    override fun summarize(p: Params): String =
        "休憩をはさまず${p.int(KEY_MINUTES, 20)}分(${p.int(KEY_BREAK_MINUTES, 10)}分あけば数え直し)"

    /**
     * 使い続けたと仮定して閾値に届く時刻。
     *
     * 既に届いているなら、次に変わりうるのは休憩を取り切った時 ──
     * いまから休みはじめても [KEY_BREAK_MINUTES] 分は成立したままなので、
     * それより早く見に来る必要がない。
     */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime {
        val breakMinutes = p.int(KEY_BREAK_MINUTES, 10).toLong()
        val remaining = p.int(KEY_MINUTES, 20) - used(p, ctx)
        return if (remaining > 0) {
            ctx.now.plusMinutes(remaining.toLong())
        } else {
            ctx.now.plusMinutes(breakMinutes)
        }
    }

    private fun used(p: Params, ctx: EvalContext): Int =
        ctx.minutesSinceBreakOf(ctx.currentRuleId, p.int(KEY_BREAK_MINUTES, 10))
}
