package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * その場で締め出す。指定した分だけ、対象が開かなくなる。
 *
 * 他のアクションが「開こうとした瞬間に何かを見せる」ものなのに対して、これは
 * **使っている最中に取り上げる**。だから条件は「n分使ったら」のような
 * 積み上がるものと組み合わせる ── [com.dopachiru.core.condition.types.UsageSinceBreakCondition]
 * が対になる相手で、あちらが数え、こちらが閉める。
 *
 * ## 「破ったら」とは別
 * 「破ったら([Consequence])」は押し切った・警告を無視した・宣言を超えた、
 * その出来事そのものに対する報いで、ポイントの増減だけを持つ。こちらは
 * 破っていなくても、時間が来れば閉まる ── 「20分使ったら10分休む」は
 * 違反ではなく取り決めなので、破ったらの欄には書けない。
 *
 * ## 押し切れない
 * 封鎖に押し切る手段は無い(時間が来れば必ず解ける)。ここが完全封印との差で、
 * ブロック画面は押し切れるぶん、慣れると1タップの儀式になる。
 * GoalKeeper(IMWUT 2019)が比べたなかで削減が最大だったのはロックだった。
 * ただし**いきなり強くすると目標そのものを緩める**(20名が緩めた)ので、
 * 既定は10分と短く、[KEY_ESCALATES] を切ったときだけ繰り返しで伸びる。
 */
object LockoutAction : ActionType {
    const val KEY_MINUTES = "minutes"
    const val KEY_SCOPE = "scope"
    const val KEY_ESCALATES = "escalates"
    const val KEY_NOTICE = "notice"

    /** 閉める範囲。 */
    object Scope {
        /** いま使っていたアプリ1つだけ。 */
        const val APP = "app"

        /** そのルールが狙っている対象ぜんぶ。タグで括っていればグループ全部。 */
        const val TARGET = "target"
    }

    override val id = "lockout"
    override val displayName = "しばらく閉め出す"
    override val description =
        "その場で取り上げる。指定した分だけ対象が開かなくなり、押し切る手段は無い。" +
            "「n分使ったらm分休む」を作るためのアクション。"

    /** 完全封印(100)より強い。押し切れないぶん、同時に成立したらこちらを採る。 */
    override val severity = 200

    /**
     * 閉め出す長さの上限(分)。
     *
     * 上限を置かないと、入力を1桁間違えただけで端末が何日も使えなくなる。
     */
    const val MAX_MINUTES = 12 * 60

    override val params = listOf(
        ParamSpec.DurationParam(
            KEY_MINUTES,
            "閉め出す長さ",
            default = 10,
            min = 1,
            max = MAX_MINUTES,
            help = "条件の「これだけ離れたら数え直す」より短くすると、明けた直後にまた閉まる",
        ),
        ParamSpec.EnumParam(
            KEY_SCOPE,
            "閉める範囲",
            options = listOf(
                ParamSpec.EnumParam.Option(Scope.TARGET, "このルールの対象ぜんぶ"),
                ParamSpec.EnumParam.Option(Scope.APP, "そのアプリだけ"),
            ),
            default = Scope.TARGET,
            help = "タグで括ってあれば、グループのアプリが全部まとめて閉まる",
        ),
        ParamSpec.BoolParam(
            KEY_ESCALATES,
            "繰り返すほど長くする",
            default = false,
            help = "同じ日に何度も引っかかったとき、1→2→4倍と伸びる。上限は${MAX_MINUTES / 60}時間",
        ),
        ParamSpec.TextParam(
            KEY_NOTICE,
            "閉め出しの画面に出す言葉",
            default = "",
            help = "空ならルール名が出る",
        ),
        ParamSpec.IntParam(
            com.dopachiru.core.action.ActionExtras.KEY_PREWARN_SECONDS,
            "閉じる前にそっと知らせる",
            default = 0,
            min = 0,
            max = com.dopachiru.core.action.ActionExtras.MAX_PREWARN_SECONDS,
            unit = "秒",
            help = "0 なら出さない。数秒だけ薄い予告を出してから閉め出す",
        ),
    )

    /** 閉める範囲を実際の対象に解決する。知らない値は対象ぜんぶに倒す。 */
    fun resolveTarget(p: Params, packageName: String, ruleTarget: Target): Target =
        if (p.string(KEY_SCOPE, Scope.TARGET) == Scope.APP) Target(packages = setOf(packageName)) else ruleTarget

    /**
     * [repeatIndex] 回目(0始まり)の、閉め出す長さ(分)。
     *
     * 段階を切っていれば毎回同じ。入れていれば 1→2→4→8 倍と伸びる。
     * 上限は必ず [MAX_MINUTES] で止まる。
     */
    fun minutesFor(p: Params, repeatIndex: Int): Int {
        val base = p.int(KEY_MINUTES, 10).coerceAtLeast(1)
        if (!p.bool(KEY_ESCALATES, false) || repeatIndex <= 0) return base.coerceAtMost(MAX_MINUTES)
        // 8回目より先は伸ばさない。Int が溢れるより先に上限で止まるが、
        // 計算の途中で溢れないよう段数のほうを抑えておく
        val steps = repeatIndex.coerceAtMost(8)
        val scaled = base.toLong() shl steps
        return scaled.coerceAtMost(MAX_MINUTES.toLong()).toInt()
    }

    override fun summarize(p: Params): String {
        val minutes = p.int(KEY_MINUTES, 10)
        val escalates = if (p.bool(KEY_ESCALATES, false)) "(繰り返すほど長く)" else ""
        val prewarn = com.dopachiru.core.action.ActionExtras.prewarnSeconds(p)
        val head = if (prewarn > 0) "そっと知らせてから" else ""
        return "${head}閉じて、このあと${minutes}分は使えなくする$escalates"
    }
}
