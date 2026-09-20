package com.dopachiru.core.param

import com.dopachiru.core.time.ResetPolicy

/**
 * 条件・アクションが要求するパラメータの型定義。
 *
 * 設定画面の入力UIはこの定義から機械的に組み立てられるため、新しい条件を足すときに
 * UI 側のコードを書き足す必要がない。ここに新しい種類を増やしたときだけ、
 * app モジュールの ParamEditor に対応する入力ウィジェットを1つ足すことになる。
 */
sealed interface ParamSpec {
    /** Params の中でこの値を引くキー。条件の中で一意であればよい。 */
    val key: String

    /** 設定画面に出すラベル。 */
    val label: String

    /** ラベルの下に出す補足。空なら出さない。 */
    val help: String

    /**
     * この欄を出す条件。`null` ならいつでも出す。
     *
     * 「どこで数え直すか」で選ばなかったぶんの欄を畳むためにある。
     * 関係ない欄が3つ並んでいると、**どれを触ればいいのか分からない**。
     *
     * 判定には関わりません ── 隠れていても値は保存されたままで、
     * 選び直せばそのまま戻ります(消すと、切り替えるたびに打ち直しになる)。
     */
    val visibleWhen: Visibility? get() = null

    /** [visibleWhen] の中身。[key] の値が [values] のどれかなら出す。 */
    data class Visibility(val key: String, val values: Set<String>)

    /** 整数。使用回数や閾値など。 */
    data class IntParam(
        override val key: String,
        override val label: String,
        val default: Int,
        val min: Int = 0,
        val max: Int = 9_999,
        val unit: String = "",
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec

    /** 一日の中の時刻。0..1439 の「その日の何分目か」で保持する。 */
    data class TimeOfDayParam(
        override val key: String,
        override val label: String,
        val default: Int,
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec

    /** 経過時間。分で保持する。 */
    data class DurationParam(
        override val key: String,
        override val label: String,
        val default: Int,
        val min: Int = 1,
        val max: Int = 24 * 60,
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec

    data class BoolParam(
        override val key: String,
        override val label: String,
        val default: Boolean,
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec

    /** 曜日の集合。java.time.DayOfWeek の value(月=1 .. 日=7)を保持する。 */
    data class DayOfWeekParam(
        override val key: String,
        override val label: String,
        val default: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec

    data class TextParam(
        override val key: String,
        override val label: String,
        val default: String = "",
        val multiline: Boolean = false,
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec

    /**
     * アプリの集合。パッケージ名(Windows では実行ファイル名)を保持する。
     *
     * ルールの「対象アプリ」とは別物。条件の中でアプリを指すために要る
     * (「直前に使っていたのがこのアプリなら」など)。
     */
    data class PackagesParam(
        override val key: String,
        override val label: String,
        val default: Set<String> = emptySet(),
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec

    /**
     * ほかのルールを1つ指す。保存するのは [com.dopachiru.core.model.Rule.uid]。
     *
     * **id ではなく uid を保存する**のは、id が端末ごとに独立して振られるため。
     * 端末をまたぐ話(連動)で id を使うと、別の端末では違うルールを指す。
     *
     * 空文字は「このルール自身」。いちばん多い使い方なので、選ばなくても通す。
     */
    data class RuleRefParam(
        override val key: String,
        override val label: String,
        val default: String = "",
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec

    /** 選択肢から1つ。value が保存され、label が表示される。 */
    data class EnumParam(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        val default: String,
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec {
        data class Option(val value: String, val label: String)
    }

    /** 集計をリセットする周期。「半日ごと」「毎日4時起点」などを表す。 */
    data class ResetPolicyParam(
        override val key: String,
        override val label: String,
        val default: ResetPolicy = ResetPolicy(),
        override val help: String = "",
        override val visibleWhen: Visibility? = null,
    ) : ParamSpec
}
