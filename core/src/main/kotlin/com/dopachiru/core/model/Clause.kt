package com.dopachiru.core.model

import com.dopachiru.core.param.Params
import kotlinx.serialization.Serializable

/**
 * 「これを出す」ひとつぶん。
 *
 * 保存の形は今までと同じ `actionId + params`。並べられるようにするために
 * 型として括り出しただけで、中身は変わっていません。
 */
@Serializable
data class ActionSpec(
    val actionId: String,
    val params: Params = Params.EMPTY,
)

/**
 * 「この条件を満たしたら、こうする」の1組。
 *
 * ## なぜルールを組に割るのか
 *
 * 同じアプリに対して、**条件ごとに別のことをしたい**からです。
 *
 *     [22時以降] → 2分待たせる
 *     [前回から3時間あいていない] → 閉じる
 *
 * これまではルールを2本に分けるしかありませんでした。分けても動きますが、
 * 一覧に同じアプリのルールが並んで何が効いているのか読めなくなるうえ、
 * **数える財布がルール単位なので、持ち時間を組ごとに分けられません**
 * (「午前は30分・夜は60分」が書けない)。
 *
 * ## 1組目はルール本体に置いたままです
 *
 * [Rule.condition] と [Rule.actionId] が1組目で、2組目以降が
 * [Rule.extraClauses]。**対称ではありません。** そうしてあるのは、
 *
 *  1. 既存の保存をそのまま読める(欄を増やすだけで、動かさない)
 *  2. **既存のルールの数える鍵が変わらない** ── 振り直すと、
 *     更新した瞬間にみんなの持ち時間の窓がリセットされる
 *
 * 読むときは [Rule.clauses] を使ってください。そこでは1組目も同じ形に揃います。
 */
@Serializable
data class Clause(
    /**
     * ルールの中で一意な番号。**数える財布の鍵**になります。
     *
     * 1組目は必ず 1。振り直してはいけません([Clauses.nextId])。
     */
    val id: Int = 1,

    val condition: ConditionNode = ConditionNode.AllOf(),

    /**
     * 出すもの。先頭が主の動作で、2つ目以降は重ねるもの。
     *
     * **いまのエンジンは先頭しか実行しません。** 重ねられるようにするのは
     * 画面を作り直すときで、器だけ先に用意してあります。
     */
    val actions: List<ActionSpec> = emptyList(),

    /** 画面に出す名前。空なら条件から作る。 */
    val name: String = "",
) {
    /** 主の動作。無ければ null(壊れた組は評価から外す)。 */
    val mainAction: ActionSpec? get() = actions.firstOrNull()

    /** 重ねる動作。いまは実行されない。 */
    val overlays: List<ActionSpec> get() = actions.drop(1)
}

object Clauses {
    /** 1組目の番号。ルール本体に置いてある組がこれ。 */
    const val FIRST_ID = 1

    /**
     * 次に振る番号。
     *
     * **使った番号は使い回しません。** 消した組の番号を再利用すると、
     * 消す前の組で数えていた持ち時間を新しい組が引き継いでしまいます。
     */
    fun nextId(clauses: List<Clause>): Int = (clauses.maxOfOrNull { it.id } ?: FIRST_ID) + 1
}
