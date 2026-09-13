package com.dopachiru.core.model

import com.dopachiru.core.action.types.LockoutAction
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
