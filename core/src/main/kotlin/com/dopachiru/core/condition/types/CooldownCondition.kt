package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/**
 * 前回の使用から一定時間あくまで成立する = そのあいだ開けなくする。
 *
 * [QuickReopenCondition] の兄。あちらは「閉じて数分でまた開く」反射的な確認行動を
 * 数分単位で捉える。こちらは時間単位で、**間隔そのものを空けさせる**ためのもの。
 * 「一度見たら次は3時間あける」のように、使う回数を構造的に減らす。
 *
 * ## いつ判定するか
 * 開いた瞬間の「前回からの間隔」で見る。開いている最中は間隔が伸びないので、
 * 使っている途中で急に開くわけではない ── [EvalContext.minutesSinceLastUseOf] が
 * 開いた時刻を基準に測る(そうしないと開きっぱなしで間隔が育って途中で解ける)。
 *
 * 対象がタグなら**グループ全体**で最後に触った時刻を見る。
 * X を見て10分後に YouTube、では「SNSは3時間あける」の意味が無い。
 */
object CooldownCondition : ConditionType {
    const val KEY_HOURS = "hours"
    const val KEY_MINUTES = "minutes"

    override val id = "cooldown_since_use"
    override val displayName = "前回の使用から間をあける"
    override val description =
        "前に使い終わってから指定した時間が経つまで成立する。そのあいだ開けなくして、使う間隔を空けさせる。"

    override val group = ConditionGroup.TRIGGER
    override val example = "前に使い終わってから3時間たつまで開かせない"

    override val params = listOf(
        ParamSpec.IntParam(KEY_HOURS, "あける時間", default = 3, min = 0, max = 48, unit = "時間"),
        ParamSpec.IntParam(KEY_MINUTES, "あける時間(分の端数)", default = 0, min = 0, max = 59, unit = "分"),
    )

    private fun thresholdMinutes(p: Params): Int =
        p.int(KEY_HOURS, 3) * 60 + p.int(KEY_MINUTES, 0)

    override fun evaluate(p: Params, ctx: EvalContext): Boolean {
        val threshold = thresholdMinutes(p)
        if (threshold <= 0) return false
        // 一度も使っていなければ間隔は無い = 開いてよい
        val since = ctx.minutesSinceLastUseOf(ctx.currentRuleId) ?: return false
        return since < threshold
    }

    override fun summarize(p: Params): String {
        val h = p.int(KEY_HOURS, 3)
        val m = p.int(KEY_MINUTES, 0)
        val span = when {
            h > 0 && m > 0 -> "${h}時間${m}分"
            h > 0 -> "${h}時間"
            else -> "${m}分"
        }
        return "前回から${span}あくまで"
    }

    /**
     * 間隔が閾値に届く時刻まで成否は変わらない。届いていれば、時間では変わらない。
     */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime {
        val threshold = thresholdMinutes(p)
        val since = ctx.minutesSinceLastUseOf(ctx.currentRuleId)
        if (since == null || threshold <= 0) return ctx.now.plusDays(1)
        val remaining = threshold - since
        return if (remaining > 0) ctx.now.plusMinutes(remaining.toLong()) else ctx.now.plusDays(1)
    }
}
