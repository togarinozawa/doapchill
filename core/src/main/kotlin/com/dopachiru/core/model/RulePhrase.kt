package com.dopachiru.core.model

import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.param.Params

/**
 * ルールを1つの文にする。
 *
 * 作っている最中に**いま何を作っているのかが読める**ようにするためのもの。
 * 編集画面が縦に長いと、上の欄と下の欄の関係が見えなくなり、
 * 「思っていたのと違うルール」が出来上がる。
 * 部品を全部見せる代わりに、**組み上がった結果を1行で返す**。
 *
 * 出す文はいつも「なにを / いつ / どうする」の順。
 * 欠けている部分はそのまま欠けていると分かる言葉にする ── 空欄を隠すと、
 * 未完成のルールを完成したものと取り違える。
 */
object RulePhrase {

    /**
     * @param labelOf パッケージ名を人が読む名前にする。端末ごとに違うので外から渡す。
     */
    fun of(
        target: Target,
        condition: ConditionNode,
        actionId: String,
        actionParams: Params = Params.EMPTY,
        labelOf: (String) -> String = { it },
    ): String {
        val what = describeTarget(target, labelOf)
        val whenPart = describeCondition(condition)
        val how = describeAction(actionId, actionParams)
        return if (whenPart.isBlank()) "$what を $how" else "$what を $whenPart $how"
    }

    /** 対象の部分だけ。「Twitter と Instagram」「youtube.com/shorts」「全アプリ(3つを除く)」 */
    fun describeTarget(target: Target, labelOf: (String) -> String = { it }): String {
        if (target.matchAll) {
            val excepted = target.exceptPackages.size + target.exceptTags.size
            return if (excepted == 0) "全アプリ" else "全アプリ(${excepted}つを除く)"
        }

        val parts = buildList {
            addAll(target.packages.map(labelOf))
            addAll(target.tags.map { "#$it" })
            addAll(target.sites.map { SitePattern.normalize(it) })
        }
        return when {
            parts.isEmpty() -> "(対象がまだ空)"
            parts.size <= 3 -> parts.joinToString("と")
            else -> parts.take(2).joinToString("と") + "ほか${parts.size - 2}件"
        }
    }

    /** 条件の部分。無条件なら空文字を返す(「いつでも」は文にすると冗長なので)。 */
    fun describeCondition(condition: ConditionNode): String {
        val described = ConditionTree.describe(condition)
        return if (described == "条件なし") "" else "$described のとき"
    }

    /** 措置の部分。「止める」「警告する」など。 */
    fun describeAction(actionId: String, params: Params = Params.EMPTY): String {
        val action = ActionRegistry[actionId] ?: return "何かする"
        val detail = runCatching { action.summarize(params) }.getOrDefault("")
        return if (detail.isBlank()) action.displayName else detail
    }

    /**
     * 名前を付けていないルールに当てる仮の名前。
     *
     * 名前を必須にすると、**中身より先に名前を考えさせる**ことになる。
     * やりたいことは決まっているのに手が止まるので、中身から作って後から直せるようにする。
     */
    fun suggestName(
        target: Target,
        condition: ConditionNode,
        labelOf: (String) -> String = { it },
    ): String {
        val what = describeTarget(target, labelOf)
        val whenPart = ConditionTree.describe(condition)
        return when {
            what.startsWith("(") -> ""
            whenPart == "条件なし" -> what
            else -> "$what / $whenPart"
        }.take(40)
    }
}
