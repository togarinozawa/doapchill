package com.dopachiru.core.model

import com.dopachiru.core.param.Params
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ルールの対象アプリ。
 *
 * 二通りの指定ができる。
 *  - 正指定: パッケージ名とタグの和。「この5個を止める」
 *  - 全指定: [matchAll] を立てて、例外を [exceptPackages] / [exceptTags] に置く。
 *    「必要なもの以外ぜんぶ止める」= 許可リスト型。
 *
 * 除外は正指定より常に強い。全指定と併用したときだけ意味があるわけではなく、
 * 正指定に対しても効く(タグで広く取って数個だけ抜く、という書き方ができる)。
 *
 * 電話やランチャーのような「絶対に止めてはいけないアプリ」は、ここではなく
 * app 側の ProtectedApps で弾く。ユーザーが設定から外せてしまってはいけないので。
 */
@Serializable
data class Target(
    val packages: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    /** 全アプリを対象にする。例外は except* で指定する。 */
    val matchAll: Boolean = false,
    val exceptPackages: Set<String> = emptySet(),
    val exceptTags: Set<String> = emptySet(),
) {
    fun matches(packageName: String, tagsOfApp: Set<String>): Boolean {
        if (packageName in exceptPackages) return false
        if (exceptTags.any { it in tagsOfApp }) return false
        if (matchAll) return true
        return packageName in packages || tags.any { it in tagsOfApp }
    }

    val isEmpty: Boolean get() = !matchAll && packages.isEmpty() && tags.isEmpty()
}

/**
 * 条件の木。AND / OR / NOT で入れ子にできる。
 *
 * ver.1 の設定画面は「複数条件の AND」までしか組ませないが、データ構造としては
 * 最初から入れ子を持てるようにしてある。将来アプリ内に条件エディタを載せたとき、
 * 保存済みのルールをそのまま読み書きできる。
 */
@Serializable
sealed interface ConditionNode {

    @Serializable
    @SerialName("leaf")
    data class Leaf(val typeId: String, val params: Params) : ConditionNode

    /** 子がすべて成立したら成立。子が空なら常に成立(= 無条件 = 完全封印)。 */
    @Serializable
    @SerialName("allOf")
    data class AllOf(val children: List<ConditionNode> = emptyList()) : ConditionNode

    /** 子のどれかが成立したら成立。子が空なら成立しない。 */
    @Serializable
    @SerialName("anyOf")
    data class AnyOf(val children: List<ConditionNode> = emptyList()) : ConditionNode

    @Serializable
    @SerialName("not")
    data class Not(val child: ConditionNode) : ConditionNode
}

/**
 * ルール = 対象 × 条件 × アクション。
 */
@Serializable
data class Rule(
    val id: Long = 0L,
    val name: String,
    val enabled: Boolean = true,
    val target: Target,
    val condition: ConditionNode,
    val actionId: String,
    val actionParams: Params = Params.EMPTY,
)
