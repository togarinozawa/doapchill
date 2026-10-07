package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.model.PeekAllowance
import com.dopachiru.core.model.Peeks
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/** 完全封印。全画面のブロック画面を出し、しばらく閉じられなくする。 */
object BlockAction : ActionType {
    const val KEY_REFLECTION = "reflection"
    const val KEY_MIN_SECONDS = "minSeconds"
    const val KEY_COVER_SYSTEM_BARS = "coverSystemBars"
    const val KEY_ALLOW_OVERRIDE = "allowOverride"
    const val KEY_RELEASE_EFFORT = "releaseEffort"
    const val KEY_PEEK = "peek"
    const val KEY_PEEK_MINUTES = "peekMinutes"
    const val KEY_PEEK_COUNT = "peekCount"
    const val KEY_PEEK_GAP = "peekGapMinutes"
    const val KEY_PEEK_PER_APP = "peekPerApp"
    const val KEY_PEEK_EFFORT = "peekEffort"

    /** のぞきの欄は、のぞきを入れたときだけ出す。 */
    private val WHEN_PEEK = ParamSpec.Visibility(KEY_PEEK, setOf("true"))

    // params より前に置く。object の初期化は上から順なので、後ろに置くと null を読む
    private val DEFAULT_PEEK = PeekAllowance()

    /** 押し切るのに要る手間。 */
    object Effort {
        /** ボタンを1回押すだけ。 */
        const val TAP = "tap"

        /** 3秒押し続ける。 */
        const val HOLD = "hold"

        /** 決められた言葉を打ち込む。 */
        const val TYPE = "type"
    }

    override val id = "block"
    override val displayName = "条件のあいだ使えなくする"
    override val description =
        "全画面でブロック画面を出す。**一度閉じて終わりではない** ── 条件が続くかぎり、" +
            "開き直すたびに同じ壁が立つ。条件が外れたら開く。"
    override val severity = 100

    override val params = listOf(
        ParamSpec.TextParam(
            KEY_REFLECTION,
            "反省文",
            default = "これを開こうとした理由を、いま一度考える。",
            multiline = true,
            help = "改行で分けると、開くたびに1つずつ選ばれる。同じ文が続くと慣れて効かなくなる",
        ),
        ParamSpec.IntParam(
            KEY_MIN_SECONDS,
            "閉じられるまで",
            default = 15,
            min = 0,
            max = 300,
            unit = "秒",
        ),
        ParamSpec.BoolParam(
            KEY_COVER_SYSTEM_BARS,
            "ナビゲーションバーごと覆う",
            default = true,
            help = "オフにすると戻る・ホームがすぐ押せるぶん抑止力が下がる",
        ),
        ParamSpec.BoolParam(
            KEY_ALLOW_OVERRIDE,
            "押し切って使えるようにする",
            default = true,
            help = "オフにすると逃げ道が無くなる。学習予定中は、この設定に関わらず押し切れない",
        ),
        // 警告ダイアログの92%は「使い続ける」で無視された(GoalKeeper, IMWUT 2019)。
        // 1タップで通れるものは、事実上そこに無いのと変わらない。
        ParamSpec.EnumParam(
            KEY_RELEASE_EFFORT,
            "押し切るのに要る手間",
            options = listOf(
                ParamSpec.EnumParam.Option(Effort.TAP, "1回押す"),
                ParamSpec.EnumParam.Option(Effort.HOLD, "3秒押し続ける"),
                ParamSpec.EnumParam.Option(Effort.TYPE, "言葉を打ち込む"),
            ),
            default = Effort.HOLD,
            help = "1タップで通れる警告は92%が無視される。手を動かさせるほど効く",
        ),
        ParamSpec.IntParam(
            com.dopachiru.core.action.ActionExtras.KEY_PREWARN_SECONDS,
            "閉じる前にそっと知らせる",
            default = 0,
            min = 0,
            max = com.dopachiru.core.action.ActionExtras.MAX_PREWARN_SECONDS,
            unit = "秒",
            help = "0 なら出さない。数秒だけ薄い予告を出してから閉じる",
        ),
        // 押し切りより軽い出口。押し切りは残す ── 重さの違う出口を2つ並べる
        ParamSpec.BoolParam(
            KEY_PEEK,
            "◯分だけのぞけるようにする",
            default = false,
            help = "押し切らずに、決めた長さだけ開ける。回数は1日(朝4時区切り)で数える",
        ),
        ParamSpec.IntParam(
            KEY_PEEK_MINUTES,
            "1回にのぞける長さ",
            default = DEFAULT_PEEK.minutes,
            min = Peeks.MIN_MINUTES,
            max = Peeks.MAX_MINUTES,
            unit = "分",
            visibleWhen = WHEN_PEEK,
        ),
        ParamSpec.IntParam(
            KEY_PEEK_COUNT,
            "1日にのぞける回数",
            default = DEFAULT_PEEK.maxCount,
            min = 1,
            max = Peeks.MAX_COUNT,
            unit = "回",
            visibleWhen = WHEN_PEEK,
        ),
        ParamSpec.IntParam(
            KEY_PEEK_GAP,
            "前ののぞきから空ける",
            default = DEFAULT_PEEK.gapMinutes,
            min = 0,
            max = Peeks.MAX_GAP_MINUTES,
            unit = "分",
            help = "これが無いと、短いのぞきを続けて並べて結局ずっと使える",
            visibleWhen = WHEN_PEEK,
        ),
        ParamSpec.EnumParam(
            KEY_PEEK_PER_APP,
            "回数の数え方",
            options = listOf(
                ParamSpec.EnumParam.Option("false", "対象ぜんぶで"),
                ParamSpec.EnumParam.Option("true", "アプリごとに"),
            ),
            default = DEFAULT_PEEK.perApp.toString(),
            visibleWhen = WHEN_PEEK,
        ),
        ParamSpec.EnumParam(
            KEY_PEEK_EFFORT,
            "のぞくのに要る手間",
            options = listOf(
                ParamSpec.EnumParam.Option(Effort.TAP, "1回押す"),
                ParamSpec.EnumParam.Option(Effort.HOLD, "3秒押し続ける"),
                ParamSpec.EnumParam.Option(Effort.TYPE, "言葉を打ち込む"),
            ),
            default = DEFAULT_PEEK.effort,
            visibleWhen = WHEN_PEEK,
        ),
    )

    /** このブロックで使えるのぞき。入れていなければ null。 */
    fun peekOf(p: Params): PeekAllowance? {
        if (!p.bool(KEY_PEEK, false)) return null
        return PeekAllowance(
            minutes = p.int(KEY_PEEK_MINUTES, DEFAULT_PEEK.minutes),
            maxCount = p.int(KEY_PEEK_COUNT, DEFAULT_PEEK.maxCount),
            gapMinutes = p.int(KEY_PEEK_GAP, DEFAULT_PEEK.gapMinutes),
            perApp = p.string(KEY_PEEK_PER_APP, DEFAULT_PEEK.perApp.toString()) == "true",
            effort = p.string(KEY_PEEK_EFFORT, DEFAULT_PEEK.effort),
        ).normalized()
    }

    override fun summarize(p: Params): String {
        val firm = if (p.bool(KEY_ALLOW_OVERRIDE, true)) "やんわり" else "しっかり"
        val prewarn = com.dopachiru.core.action.ActionExtras.prewarnSeconds(p)
        val head = if (prewarn > 0) "そっと知らせてから" else ""
        val peek = if (peekOf(p) != null) "・のぞける" else ""
        return head + "条件のあいだ使えなくする($firm$peek)"
    }
}
