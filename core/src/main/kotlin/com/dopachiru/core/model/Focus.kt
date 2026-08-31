package com.dopachiru.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 集中モードの決まりごと。
 *
 * ## 何のためのものか
 *
 * 食事のあいだスマホを見てしまい、そのまま時間が延びる ── のような、
 * **その場かぎりで一時的に手を止めたい**とき用。
 * ルールが「いつ・どの条件で」を先に決めておくものなのに対して、
 * これは思い立った瞬間に始めて、時間が来たら勝手に解ける。
 *
 * ## 短く始めて足す
 *
 * 既定を短くしてあるのは、**長く始めすぎたときの逃げ方が高くつく**ため。
 * 30分で始めて20分で用が済むと、残り10分を解くのに手間とポイントを払うことになり、
 * 悪いことをしていないのに罰されている感じになる。
 * 短く始めて、足りなければ足すほうが素直で、しかも「もう少しだけ」を
 * 自分で選ぶことになるので、続けている自覚も残る。
 *
 * ## 止める仕組みは罰と同じもの
 *
 * 実体は [Lockout]。違うのは [Lockout.earlyExit] が入っていることだけで、
 * 判定も保存も期限切れも罰と同じ道を通る。
 */
object Focus {

    /** 長さは5分刻み。 */
    const val STEP_MINUTES = 5

    const val MIN_MINUTES = 5

    /** 上限。これ以上は「集中」ではなく、ルールで決めるべきもの。 */
    const val MAX_MINUTES = 8 * 60

    /** 既定の長さ。短く始めて足す前提なので控えめにしてある。 */
    const val DEFAULT_MINUTES = 15

    /** ホーム画面のショートカットが持つ長さ。 */
    const val SHORTCUT_MINUTES = 15

    /** 押し間違いを無料で取り消せる時間(秒)。 */
    const val FREE_CANCEL_SEC = 60L

    /** 一度に足せる長さ。 */
    val EXTEND_CHOICES = listOf(5, 10, 15, 30)

    fun clampMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_MINUTES, MAX_MINUTES) / STEP_MINUTES * STEP_MINUTES

    /**
     * 集中の封鎖を1つ作る。
     *
     * @param allowPackages 集中中も開けたままにするもの。音楽や時計など。
     *   電話・緊急発信・ホーム・設定は、ここに入れなくても端末側の床で必ず開く。
     */
    fun start(
        nowSec: Long,
        minutes: Int,
        allowPackages: Set<String> = emptySet(),
        allowTags: Set<String> = emptySet(),
        effort: String = "hold",
        abortPoints: Int = 0,
        label: String = "",
    ): Lockout {
        val length = clampMinutes(minutes)
        return Lockout(
            uid = UUID.randomUUID().toString(),
            target = Target(
                matchAll = true,
                exceptPackages = allowPackages,
                exceptTags = allowTags,
            ),
            untilEpochSec = nowSec + length * 60L,
            reason = label.ifBlank { "自分で始めた集中" },
            createdAtEpochSec = nowSec,
            earlyExit = EarlyExit(
                effort = effort,
                points = abortPoints,
                freeUntilEpochSec = nowSec + FREE_CANCEL_SEC,
            ),
        )
    }

    /** 時間を足す。上限は始めた時刻からの [MAX_MINUTES]。 */
    fun extend(lockout: Lockout, addMinutes: Int, nowSec: Long): Lockout {
        val ceiling = lockout.createdAtEpochSec + MAX_MINUTES * 60L
        val extended = (lockout.untilEpochSec + addMinutes * 60L).coerceAtMost(ceiling)
        // 期限切れのものを延ばして生き返らせない
        if (!lockout.isActiveAt(nowSec)) return lockout
        return lockout.copy(untilEpochSec = extended)
    }

    /** 走っている集中。罰は含まない。 */
    fun activeIn(all: List<Lockout>, nowSec: Long): Lockout? =
        all.filter { it.isChosen && it.isActiveAt(nowSec) }.maxByOrNull { it.untilEpochSec }
}

/** 集中モードの既定値。端末ごとに持つ。 */
@Serializable
data class FocusSettings(
    val defaultMinutes: Int = Focus.DEFAULT_MINUTES,
    /** ホーム画面のショートカットで始まる長さ。 */
    val shortcutMinutes: Int = Focus.SHORTCUT_MINUTES,
    /** 集中中も開けたままにするアプリ。 */
    val allowPackages: Set<String> = emptySet(),
    val allowTags: Set<String> = emptySet(),
    /** 途中でやめるときの手間。BlockAction.Effort の値。 */
    val abortEffort: String = "hold",
)
