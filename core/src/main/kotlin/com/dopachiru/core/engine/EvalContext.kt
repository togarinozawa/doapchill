package com.dopachiru.core.engine

import java.time.LocalDateTime

/**
 * 1回のルール評価に渡される「今の状況」。
 *
 * 新しい条件が新しい種類の入力を必要とするようになったら、ここにフィールドを1つ足す。
 * 条件の側は必要なものだけを読むので、既存の条件には影響しない。
 */
data class EvalContext(
    /** 評価時刻(端末のローカル時刻)。 */
    val now: LocalDateTime,

    /** 今フォアグラウンドにあるアプリのパッケージ名。 */
    val packageName: String,

    /**
     * いま見ているページの URL。ブラウザ以外では null。
     *
     * ブラウザは1つのアプリなので、これが無いと「YouTube のショートだけ」が
     * 書けない。取れなかったときは null のままにすること ── 空文字にすると
     * 「URL は取れたが何にも当たらない」と区別が付かなくなる。
     */
    val url: String? = null,

    /** そのアプリの使用実績。 */
    val usage: UsageSnapshot,

    /** カレンダーの状況。権限が無ければ [CalendarState.NONE]。 */
    val calendar: CalendarState = CalendarState.NONE,

    /** 学習予定の状況。連携していなければ [StudyState.NONE]。 */
    val study: StudyState = StudyState.NONE,

    /** 端末が省電力モードか。 */
    val powerSaveMode: Boolean = false,

    /**
     * 「開く前に宣言させる」で申告した残り時間(分)。
     * 宣言していなければ null、宣言ぶんを使い切っていれば 0 以下。
     */
    val declaredRemainingMinutes: Int? = null,

    /**
     * この直前に前面にあったアプリ。ホームから開いたなら null。
     *
     * 「LINE を見た流れで X を開く」のようなアプリ連鎖(App Habits)を捉えるため。
     * 習慣化した起動の多くは、単独ではなく連鎖の一部として起きる。
     */
    val previousPackage: String? = null,

    /**
     * いまのセッションを識別する種。開くたびに変わり、開いているあいだは変わらない。
     *
     * 確率で発火する条件が、同じセッションのあいだ同じ答えを返すために要る。
     * 毎回引き直すと、ブロックが数秒おきに出たり消えたりする。
     */
    val sessionSeed: Long = 0L,

    /** いま評価しているルールの ID。エンジンが差し込む。 */
    val currentRuleId: Long = 0L,

    /**
     * そのルールを最近何回押し切ったか。慣れの検出に使う。
     *
     * 同じ介入は1日ごとに25%効果が落ちる(HabitLab)。効かなくなったことを
     * 見つけて強度を上げるには、「効いていない」を測れる必要がある。
     */
    val overrideCountOf: (ruleId: Long) -> Int = { 0 },
)
