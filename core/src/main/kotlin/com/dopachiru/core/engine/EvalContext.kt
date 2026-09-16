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
     * そのルールの対象アプリをまとめて数えた、休憩をはさむまでの使用時間(分)。
     *
     * 対象の解決(タグからアプリを引く)も実測の持ち方も端末側の都合なので、
     * ここでは関数として受け取る。渡されなければ 0 ── 数えられない端末で
     * 「使っていないことにする」ほうへ倒す。閉め出しが空振りするだけで済む。
     *
     * @param breakMinutes これだけ対象を触っていない時間があれば、そこから数え直す。
     */
    val minutesSinceBreakOf: (ruleId: Long, breakMinutes: Int) -> Int = { _, _ -> 0 },

    /**
     * そのルールの対象について、いま張られている「持ち時間の窓」。
     *
     * 窓は最初に触った時刻に張られ、閉じても消えない。[minutesSinceBreakOf] が
     * 「離れたら数え直す」なのに対し、こちらは**壁時計に釘を打つ** ── ギリギリで
     * 閉じて数え直させる手を塞ぐためにある。詳しくは [UsageWindows]。
     *
     * 渡されなければ窓なし。数えられない端末では条件が成立しないだけで済む。
     *
     * @param windowMinutes 窓の幅(分)。
     */
    val windowUsageOf: (ruleId: Long, windowMinutes: Int) -> WindowUsage = { _, _ -> WindowUsage.NONE },

    /**
     * そのルールの対象アプリを、前回いつまで使っていたか ── いまから何分前に
     * 最後の使用が終わったか。まだ一度も使っていなければ null。
     *
     * 「前回からN時間あけないと開けない」を書くために要る。対象がタグなら
     * グループ全体で最後に触った時刻を見る。いま開いている一続きは数えない
     * (開いた瞬間に判定するので、その回の手前が「前回」)。
     */
    val minutesSinceLastUseOf: (ruleId: Long) -> Int? = { null },

    /**
     * いま前面のアプリが、予約された時間帯の中に居るか。
     *
     * 予約は「冷静なうちに、この時間だけ使うと先に決めておく」もの。
     * 予約の中でなければ塞ぐルール(outside_reservation)がこれを読む。
     * 予約の突き合わせ(どの予約がどのアプリに効くか)は端末側でやる ──
     * core は「いま予約の中か外か」の答えだけを受け取る。
     */
    val withinReservation: Boolean = false,

    /**
     * いま前面に出ている画面の目印。[com.dopachiru.core.model.ScreenSignals] の値。
     *
     * 「YouTube のショートだけ」「Instagram のリールだけ」のように、
     * アプリの中の特定の画面を狙うために要る。取り方は端末側の都合
     * (Android はノードツリー、ブラウザは URL)なので、core は目印の集合だけを受け取る。
     * 取れなければ空 ── 空を「当たらない」に倒すことで、目印を出せない端末で
     * 画面ルールが暴発しない。
     */
    val screenSignals: Set<String> = emptySet(),

    /**
     * そのルールを最近何回押し切ったか。慣れの検出に使う。
     *
     * 同じ介入は1日ごとに25%効果が落ちる(HabitLab)。効かなくなったことを
     * 見つけて強度を上げるには、「効いていない」を測れる必要がある。
     */
    val overrideCountOf: (ruleId: Long) -> Int = { 0 },
)
