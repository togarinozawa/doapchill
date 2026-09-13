package com.dopachiru.core.model

import kotlinx.serialization.Serializable

/**
 * タイマーロックの止める範囲。
 *
 * 自分で始める集中([Focus])は、これまで「逃がすもの以外ぜんぶ」の一択だった。
 * だが用途によって止めたい範囲は違う ── 仕事に集中したいなら SNS だけ止めれば
 * よく、逆に完全に手を止めたいなら端末ごと閉める。範囲を選べるようにする。
 */
@Serializable
enum class FocusScope(val label: String) {
    /** そのタグのアプリだけ止める。「SNS だけ1時間」 */
    GROUP("このグループだけ止める"),

    /** そのタグ以外を止める。「仕事以外を止める」= 許可リスト型の集中。 */
    EXCEPT_GROUP("このグループ以外を止める"),

    /** 逃がすもの以外ぜんぶ止める。いちばん強い。 */
    EVERYTHING("逃がすもの以外ぜんぶ止める"),
}

/**
 * タイマーロックの型(テンプレ)。「何を・どれくらい止めるか」を先に決めておく。
 *
 * ## 何のためか
 *
 * 思い立った瞬間に始められることが、そのまま使う回数になる。だが範囲や長さを
 * 毎回選ばせると、選ぶ手間で始めなくなる。型にしておけば、ホーム画面の
 * ショートカット1つ ── あるいは1タップ ── で決まった止め方を呼び出せる。
 *
 * ## 保存する形
 *
 * [FocusSettings.templates] に並べて端末ごとに持つ。範囲そのもの([Target])では
 * なく scope + tag で持つのは、タグの中身(どのアプリが属すか)が後から変わっても
 * 型は同じ意味で効いてほしいため ── 実際の [Target] は使う瞬間に [target] で組む。
 */
@Serializable
data class FocusTemplate(
    /** 端末内で一意。ショートカットの紐付けと、置き換え・削除の鍵。 */
    val id: String,

    /** 画面とショートカットに出す名前。空なら scope から見繕う。 */
    val label: String = "",

    val scope: FocusScope = FocusScope.EVERYTHING,

    /** [FocusScope.GROUP] / [FocusScope.EXCEPT_GROUP] のとき見るタグ。 */
    val tag: String = "",

    /** 0 なら「長さを選ぶ」、1以上なら1タップで始まる決め打ちの長さ(分)。 */
    val minutes: Int = 0,

    /** 常に逃がすアプリ。EXCEPT_GROUP / EVERYTHING のとき効く。 */
    val allowPackages: Set<String> = emptySet(),

    /** 常に逃がすタグ。 */
    val allowTags: Set<String> = emptySet(),
) {
    /** この型が実際に止める範囲。使う瞬間に組む。 */
    fun target(): Target = when (scope) {
        FocusScope.GROUP -> Target(tags = setOf(tag))
        FocusScope.EXCEPT_GROUP -> Target(
            matchAll = true,
            exceptTags = allowTags + tag,
            exceptPackages = allowPackages,
        )
        FocusScope.EVERYTHING -> Target(
            matchAll = true,
            exceptTags = allowTags,
            exceptPackages = allowPackages,
        )
    }

    /** 型として成り立っているか。タグを見る範囲なのにタグが空、は使えない。 */
    val isUsable: Boolean
        get() = when (scope) {
            FocusScope.GROUP, FocusScope.EXCEPT_GROUP -> tag.isNotBlank()
            FocusScope.EVERYTHING -> true
        }

    /** 1タップで始まるか。false なら長さを選ばせる。 */
    val isOneTap: Boolean get() = minutes > 0

    fun displayLabel(): String = label.ifBlank {
        when (scope) {
            FocusScope.GROUP -> "${tag.ifBlank { "グループ" }}だけ止める"
            FocusScope.EXCEPT_GROUP -> "${tag.ifBlank { "グループ" }}以外を止める"
            FocusScope.EVERYTHING -> "全部止める"
        }
    }
}
