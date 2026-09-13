package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 画面だけ覆って、音は流したままにする ── 「ラジオにする」ための措置。
 *
 * 動画を音声として流したいのに、映像に見入ってしまう・おすすめに脱線する、
 * という使い方のためのもの。完全封印([BlockAction])と違って**音は止めない**
 * ── 覆うのは映像だけ。Lukoff ら(CHI 2021)では「無関係な推薦」は
 * 100% が「制御感が減る」と答えており、推薦は意志で無視するものではなく
 * **視界から外す**ものだと分かる。これはその直接の実装。
 *
 * Duckworth ら(2016)の枠組みでは、意志で我慢する⑤ではなく、
 * 状況の魅力を下げる②状況修正にあたる。だからリアクタンスが低い。
 *
 * ## 覗ける
 * どうしても映像を見たいときのために「見る」口は残すが、手間を課す
 * ([KEY_PEEK_EFFORT])。一度覗いたら [KEY_PEEK_SECONDS] 秒だけ開けて、また覆う。
 * 塞ぎ切らないのは、レシピの手元や地図など**映像に用がある瞬間**もあるため。
 */
object RadioAction : ActionType {
    const val KEY_MESSAGE = "message"
    const val KEY_PEEK_EFFORT = "peekEffort"
    const val KEY_PEEK_SECONDS = "peekSeconds"

    override val id = "radio"
    override val displayName = "音だけにする(画面を覆う)"
    override val description =
        "音は流したまま、映像だけを覆う。動画をラジオとして使いたいとき用。覗くには手間がかかる。"

    /** 完全封印(100)より弱い。両方成立したら塞ぐほうを採る。 */
    override val severity = 70

    override val params = listOf(
        ParamSpec.TextParam(
            KEY_MESSAGE,
            "覆う画面に出す言葉",
            default = "耳で聞く。目は要らない。",
            multiline = true,
            help = "改行で分けると、覆うたびに1つずつ選ばれる",
        ),
        ParamSpec.EnumParam(
            KEY_PEEK_EFFORT,
            "覗くのに要る手間",
            options = listOf(
                ParamSpec.EnumParam.Option(BlockAction.Effort.TAP, "1回押す"),
                ParamSpec.EnumParam.Option(BlockAction.Effort.HOLD, "3秒押し続ける"),
            ),
            default = BlockAction.Effort.HOLD,
            help = "1タップで覗けると、結局ずっと見てしまう",
        ),
        ParamSpec.IntParam(
            KEY_PEEK_SECONDS,
            "一度に覗ける長さ",
            default = 10,
            min = 3,
            max = 120,
            unit = "秒",
            help = "この秒数が過ぎると、また覆います",
        ),
    )

    override fun summarize(p: Params): String = "音だけにする"
}
