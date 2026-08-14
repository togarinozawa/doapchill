package com.dopachiru.core.model

import kotlinx.serialization.Serializable

/** 罰として封鎖する範囲。 */
@Serializable
enum class LockScope(val label: String, val description: String) {
    /** 封鎖しない。ポイントだけ動く。 */
    NONE("封鎖しない", "ポイントの増減だけ"),

    /** 破ったアプリ1つだけ。 */
    APP("そのアプリだけ", "破ったアプリが、しばらく開けなくなる"),

    /** そのルールが狙っていたアプリぜんぶ。タグで括っていればグループ単位になる。 */
    RULE_TARGET("このルールの対象ぜんぶ", "タグで括っていればグループ単位で閉まる"),

    /** 逃がすものを除いた端末ぜんぶ。 */
    EVERYTHING("逃がすもの以外ぜんぶ", "選んだアプリ以外、端末全体が使えなくなる"),
}

/**
 * ルールを破った / 守ったときに起きること。
 *
 * ルールの「条件 × アクション」とは別の軸。アクションは**その場で**何を出すかを、
 * こちらは**そのあと**何が起きるかを決める。
 *
 * ポイントの増減を null にしておけるのが肝で、そのときは設定の既定値が使われる。
 * 0 を既定にしてしまうと、この機能より前に作ったルールだけポイントが動かない
 * 状態で取り残される ── 既存のルールにも黙って効いてほしいので、
 * 「指定なし = 設定に従う」を表せる形にしてある。
 */
@Serializable
data class Consequence(
    val lockScope: LockScope = LockScope.NONE,

    /** 封鎖する長さ(分)。0 なら封鎖しない。 */
    val lockMinutes: Int = 0,

    /** [LockScope.EVERYTHING] のときに逃がすアプリ。 */
    val lockAllowPackages: Set<String> = emptySet(),

    /** [LockScope.EVERYTHING] のときに逃がすタグ。 */
    val lockAllowTags: Set<String> = emptySet(),

    /** 破ったときのポイント増減。ふつうは負。null なら設定の既定値。 */
    val breakPoints: Int? = null,

    /** 引き返したときのポイント増減。ふつうは正。null なら設定の既定値。 */
    val keepPoints: Int? = null,
) {
    /** 封鎖まではしないか。 */
    val locksNothing: Boolean
        get() = lockScope == LockScope.NONE || lockMinutes <= 0

    /**
     * この罰が実際に閉める範囲。封鎖しないなら null。
     *
     * @param packageName 破ったアプリ。[LockScope.APP] のときだけ使う。
     * @param ruleTarget そのルールの対象。[LockScope.RULE_TARGET] のときだけ使う。
     */
    fun resolveTarget(packageName: String, ruleTarget: Target): Target? {
        if (locksNothing) return null
        return when (lockScope) {
            LockScope.NONE -> null
            LockScope.APP -> Target(packages = setOf(packageName))
            LockScope.RULE_TARGET -> ruleTarget
            LockScope.EVERYTHING -> Target(
                matchAll = true,
                exceptPackages = lockAllowPackages,
                exceptTags = lockAllowTags,
            )
        }
    }

    fun summarize(): String {
        val lock = if (locksNothing) null else "${lockScope.label}を${lockMinutes}分"
        val points = breakPoints?.takeIf { it != 0 }?.let { if (it < 0) "${-it}ポイント払う" else "+${it}ポイント" }
        return listOfNotNull(lock, points).joinToString(" / ").ifBlank { "設定どおり" }
    }

    companion object {
        val NONE = Consequence()

        /**
         * 封鎖の長さの上限。
         *
         * 上限を置かないと、入力を1桁間違えただけで端末が何日も使えなくなる。
         * 罰は自分で選んで科すものだが、選び間違いから戻れないのは事故なので、
         * ここだけはユーザーに越えさせない。
         */
        const val MAX_LOCK_MINUTES = 12 * 60
    }
}
