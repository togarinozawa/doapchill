package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.LockScope
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
 * ## 罰との違い
 * 見た目も効き方も罰([Consequence])と同じ封鎖だが、科される筋道が違う。
 * 罰は**破ったから**科される。こちらは破っていなくても、時間が来れば閉まる。
 * 「20分使ったら10分休む」は違反ではなく取り決めなので、罰の欄には書けない。
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

    /** 閉める範囲。[LockScope] のうち、ここから選べるものだけ。 */
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

    override val params = listOf(
        ParamSpec.DurationParam(
            KEY_MINUTES,
            "閉め出す長さ",
            default = 10,
            min = 1,
            max = Consequence.MAX_LOCK_MINUTES,
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
            help = "同じ日に何度も引っかかったとき、1→2→4倍と伸びる。上限は" +
                "${Consequence.MAX_LOCK_MINUTES / 60}時間",
        ),
        ParamSpec.TextParam(
            KEY_NOTICE,
            "閉め出しの画面に出す言葉",
            default = "",
            help = "空ならルール名が出る",
        ),
    )

    /** [KEY_SCOPE] を封鎖の範囲に読み替える。知らない値は対象ぜんぶに倒す。 */
    fun scopeOf(p: Params): LockScope =
        if (p.string(KEY_SCOPE, Scope.TARGET) == Scope.APP) LockScope.APP else LockScope.RULE_TARGET

    /**
     * 罰と同じ形に直す。
     *
     * 範囲の解決も、繰り返しで伸ばす計算も [Consequence] が持っているので、
     * 端末側は罰とまったく同じ道を通せる。閉め方を2通り持たない。
     */
    fun consequenceOf(p: Params): Consequence = Consequence(
        lockScope = scopeOf(p),
        lockMinutes = p.int(KEY_MINUTES, 10).coerceAtLeast(1),
        lockEscalates = p.bool(KEY_ESCALATES, false),
    )

    override fun summarize(p: Params): String {
        val minutes = p.int(KEY_MINUTES, 10)
        val scope = if (scopeOf(p) == LockScope.APP) "そのアプリ" else "対象ぜんぶ"
        val escalates = if (p.bool(KEY_ESCALATES, false)) "(繰り返すほど長く)" else ""
        return "${scope}を${minutes}分閉め出す$escalates"
    }
}
