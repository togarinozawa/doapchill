package com.dopachiru.core.model

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.types.UsageSinceBreakCondition
import com.dopachiru.core.param.Params

/**
 * 保存はできるが、書いたとおりには効かない組み合わせを見つける。
 *
 * 弾かずに知らせるだけにしてある。設定を禁じると「なぜ作れないのか」が
 * 分からないまま手が止まるが、注意書きなら読んで無視することもできる。
 *
 * ここに出るのは**噛み合わせ**の話だけ。値そのものの上限や下限は
 * ParamSpec 側で止まる。
 */
object RuleCheck {

    /**
     * その措置に「破る」道があるか ── つまり [Consequence] の設定が効くか。
     *
     * ## なぜ要るか
     *
     * 「どうする(措置)」と「破ったら(報い)」は別の軸だが、**片方がもう片方を無意味にする**
     * 組み合わせがある。完全封印の押し切りを切ると破りようが無いので、罰の欄は丸ごと死ぬ。
     * それでも欄が出ていると、設定したつもりで何も起きない ── 画面側はここに聞いて、
     * 効かない欄は出さないか、効かないと書く。
     *
     * 破れるのはこの3つだけ。端末側で実際に罰を科している場所と1対1で対応する:
     *  - 完全封印を**押し切った**(押し切りを許しているときだけ)
     *  - 警告を**無視した**
     *  - 宣言した時間を**超えた**
     */
    fun isBreakable(actionId: String, actionParams: Params): Boolean = when (actionId) {
        BlockAction.id -> actionParams.bool(BlockAction.KEY_ALLOW_OVERRIDE, true)
        WarnAction.id, DeclareAction.id -> true
        else -> false
    }

    /** その措置で「破る」とは何をすることか。画面に出す1行。 */
    fun breakMeans(actionId: String, actionParams: Params): String = when {
        actionId == BlockAction.id && !isBreakable(actionId, actionParams) ->
            "この措置は押し切れません。破る道が無いので、下の報いは科されません。"

        actionId == BlockAction.id -> "ブロック画面を押し切って使ったとき"
        actionId == WarnAction.id -> "警告を無視して使い続けたとき"
        actionId == DeclareAction.id -> "宣言した時間を使い切っても続けたとき"
        else -> "この措置には破る道がありません。時間が来るか、条件が外れるまで続きます。"
    }

    fun warnings(condition: ConditionNode, target: Target, actionId: String, actionParams: Params): List<String> {
        val warnings = mutableListOf<String>()
        val breakMinutes = breakMinutesIn(condition)

        // 数えるのはアプリ単位。URL だけを指した対象は、どのアプリの使用時間にも紐づかない
        if (breakMinutes != null && target.sites.isNotEmpty() &&
            !target.matchAll && target.packages.isEmpty() && target.tags.isEmpty()
        ) {
            warnings += "この条件が数えるのはアプリの使用時間です。URL だけを指した対象では数えられず、" +
                "いつまでも成立しません。ブラウザのアプリかタグも対象に入れてください。"
        }

        if (actionId != LockoutAction.id) return warnings

        val lockMinutes = actionParams.int(LockoutAction.KEY_MINUTES, 10)
        if (breakMinutes != null && breakMinutes > lockMinutes) {
            warnings += "「これだけ離れたら数え直す」が${breakMinutes}分、閉め出しが${lockMinutes}分。" +
                "閉め出しが明けても数え直されないので、そのまままた閉まります。" +
                "閉め出しを${breakMinutes}分にするか、数え直しを${lockMinutes}分に下げてください。"
        }

        if (target.matchAll && LockoutAction.scopeOf(actionParams) == LockScope.RULE_TARGET) {
            warnings += "対象が「ぜんぶ」なので、条件が成立すると端末全体が${lockMinutes}分閉まります。"
        }

        return warnings
    }

    /** 木のどこかにある「休憩をはさむまでの使用時間」の、数え直しの分数。 */
    private fun breakMinutesIn(node: ConditionNode): Int? = when (node) {
        is ConditionNode.Leaf ->
            if (node.typeId == UsageSinceBreakCondition.id) {
                node.params.int(UsageSinceBreakCondition.KEY_BREAK_MINUTES, 10)
            } else {
                null
            }

        is ConditionNode.Not -> breakMinutesIn(node.child)
        is ConditionNode.AllOf -> node.children.firstNotNullOfOrNull { breakMinutesIn(it) }
        is ConditionNode.AnyOf -> node.children.firstNotNullOfOrNull { breakMinutesIn(it) }
    }
}
