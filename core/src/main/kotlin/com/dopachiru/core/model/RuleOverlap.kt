package com.dopachiru.core.model

import com.dopachiru.core.action.ActionRegistry

/**
 * 同じ相手を狙っているルールを見つける。
 *
 * ## なぜ要るのか
 *
 * 「同じアプリでも条件ごとにアクションを変えたい」は**もともとできる**。
 * ルールを2本書けば、両方成立したときは強いほうが採られる
 * ([com.dopachiru.core.engine.RuleEngine])。
 *
 * ところが画面のどこにもそう書いていないので、**できると気づけない**。
 * 1本のルールに全部を詰め込もうとして行き詰まる。足りないのは機能ではなく、
 * 「いま何本がこのアプリを見ているか」が見えることだった。
 *
 * ## 重なりは広めに取る
 *
 * 除外まで厳密に解くと、当たるか当たらないかが実行時の状況で変わってしまう。
 * ここは**案内のための見立て**なので、広めに拾って「他にもあります」と言うほうが
 * 役に立つ ── 見落として黙っているより、余分に出すほうが安い。
 */
object RuleOverlap {

    /**
     * 2つの対象が同じアプリを捉えうるか。
     *
     * どちらかが全指定なら重なるとみなす。それ以外は、パッケージ・タグ・サイトの
     * どれかが交われば重なる。
     */
    fun overlaps(a: Target, b: Target): Boolean {
        if (a.isEmpty || b.isEmpty) return false
        if (a.matchAll || b.matchAll) return true
        if (a.packages.any { it in b.packages }) return true
        if (a.tags.any { it in b.tags }) return true
        if (a.sites.any { it in b.sites }) return true
        return false
    }

    /**
     * [rule] と同じ相手を狙っている他のルール。**強い順**に返す。
     *
     * 強い順なのは、同時に成立したときに勝つものが上に来るため ──
     * 並び順がそのまま「どれが効くか」の説明になる。
     */
    fun siblingsOf(rule: Rule, all: List<Rule>): List<Rule> =
        all.filter { it.uid != rule.uid && it.id != rule.id && overlaps(rule.target, it.target) }
            .sortedByDescending { severityOf(it) }

    /** その措置の強さ。知らない措置は 0(いちばん弱い扱い)。 */
    fun severityOf(rule: Rule): Int = ActionRegistry[rule.actionId]?.severity ?: 0

    /**
     * 同時に成立したとき、この中でどれが勝つか。全部が止まっていれば null。
     *
     * 勝つのは1本だけ ── そこを見せておかないと、「弱いほうも一緒に効く」と
     * 思い込んだまま組むことになる。
     */
    fun winnerAmong(rules: List<Rule>): Rule? =
        rules.filter { it.enabled }.maxByOrNull { severityOf(it) }
}
