package com.dopachiru.core.preset

import com.dopachiru.core.DopaFeatures
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.TimerAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.types.AppChainCondition
import com.dopachiru.core.condition.types.CalendarBusyCondition
import com.dopachiru.core.condition.types.ChanceCondition
import com.dopachiru.core.condition.types.ContinuousUsageCondition
import com.dopachiru.core.condition.types.DayOfWeekCondition
import com.dopachiru.core.condition.types.HabituationCondition
import com.dopachiru.core.condition.types.QuickReopenCondition
import com.dopachiru.core.condition.types.SessionCountCondition
import com.dopachiru.core.condition.types.StudyPrepCondition
import com.dopachiru.core.condition.types.StudySessionCondition
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.condition.types.TotalUsageCondition
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.LockScope
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy

/**
 * よくある縛りの雛形。
 *
 * 何もない状態から条件を組み立てるのは、いちばん腰が重いところ。
 * 雛形から入れて後で細部を詰められるようにしておく。
 * 選んだあとは普通のルールになるので、いくらでも編集できる。
 *
 * core に置いてあるのは、Android と Windows で同じ雛形を出すため。
 * 中身は Rule のデータだけで、画面のことは知らない。
 */
/** 雛形の並べ方。弱いものから順に見せる。 */
enum class PresetGroup(val label: String, val help: String) {
    /**
     * 遮らない措置。まずここから始める。
     *
     * 依存傾向が高い人ほど強い介入を拒む(GoalKeeper: 41.7%が最弱を選好)。
     * 最も助けが要る人が最も強い介入から降りるので、強いものを既定にしない。
     */
    GENTLE("まず軽いものから", "止めない。気づかせる・少し待たせるだけ"),

    /** 上限を決めて、超えたら止める。定番だが、強すぎると目標のほうが緩む。 */
    LIMIT("上限を決める", "使う量を先に決めて、超えたら止める"),

    /** 引き金そのものを狙う。合計時間より効率がよい。 */
    TRIGGER("開く癖を狙う", "何分使ったかではなく「なぜ開いたか」で止める"),

    /** 逃げ場を塞ぐ。効果は最大だが受け入れづらい。 */
    STRICT("強く縛る", "効果は大きいが、続かなければ意味がない"),
}

data class RulePreset(
    val id: String,
    val name: String,
    val description: String,
    /** 一覧での並び。既定は「上限を決める」。 */
    val group: PresetGroup = PresetGroup.LIMIT,
    /** この雛形の根拠。出典を添えて、なぜ効くのかを1行で。 */
    val evidence: String = "",
    /**
     * アプリを選ばせるときの見出し。
     * 許可リスト型の雛形では、選ぶのは「止めるアプリ」ではなく「残すアプリ」になる。
     */
    val appPrompt: String = "対象アプリを選んでください",
    /** 1つも選ばずに作れるか。許可リスト型だけ true。 */
    val allowEmptyApps: Boolean = false,
    /** カレンダー連携が要る雛形か。凍結中は一覧に出さない。 */
    val requiresCalendar: Boolean = false,
    private val builder: (Set<String>) -> Rule,
) {
    fun build(packages: Set<String>): Rule = builder(packages)
}

object RulePresets {

    /** いま選べる雛形。凍結した機能を使うものは出さない。 */
    val all: List<RulePreset>
        get() = defined.filter { !it.requiresCalendar || DopaFeatures.CALENDAR_ENABLED }

    private val defined: List<RulePreset> = listOf(

        // ---- まず軽いものから ------------------------------------------

        RulePreset(
            id = "session_timer",
            name = "経過時間を見せるだけ",
            description = "画面の隅に「何分見ているか」を出し続ける。止めも遮りもしない。",
            group = PresetGroup.GENTLE,
            evidence = "無限スクロールが効くのは経過時間の自覚を奪うから(ACDP #10 Time Fog)。" +
                "約30分で「無駄にした」という嫌悪感は自然に来るので、時計を見せるとそれが早く来る。",
        ) { packages ->
            rule(
                name = "経過時間を見せる",
                packages = packages,
                conditions = emptyList(),
                actionId = TimerAction.id,
                actionParams = Params.of(
                    TimerAction.KEY_AFTER_MINUTES to 3,
                    TimerAction.KEY_SHOW_TODAY to true,
                ),
            )
        },

        RulePreset(
            id = "open_delay",
            name = "開くときに数秒待たせる",
            description = "開いた直後に5秒だけ待たせて、必ず通す。押し切るボタンは無い。",
            group = PresetGroup.GENTLE,
            evidence = "遮断ではなく「報酬までの時間」を伸ばす措置。367個のツールのうち" +
                "遅延を扱うものは4%しかない(ブロックは74%)。反射で掴んだ手も数秒あれば目的を思い出せる。",
        ) { packages ->
            rule(
                name = "開くときに一拍置く",
                packages = packages,
                conditions = emptyList(),
                actionId = DelayAction.id,
                actionParams = Params.of(
                    DelayAction.KEY_SECONDS to 5,
                    DelayAction.KEY_MESSAGE to
                        "何をしに開いた?\n" +
                        "5秒後にも同じ気持ちなら、それは本物。\n" +
                        "いま見なくても、明日には消えている。\n" +
                        "これは休憩? それとも逃避?",
                ),
            )
        },

        RulePreset(
            id = "sometimes_friction",
            name = "たまにだけ引き止める",
            description = "3回に1回くらいの確率でだけ待たせる。毎回だと慣れるし、嫌われる。",
            group = PresetGroup.GENTLE,
            evidence = "毎回の摩擦は53%が苛立ち33%が継続意欲を下げた(Design Frictions 2024)。" +
                "同じ介入は1日ごとに効果25%減、回すと -34%/日(HabitLab)。出ない日があるほうが重みが残る。",
        ) { packages ->
            rule(
                name = "たまに引き止める",
                packages = packages,
                conditions = listOf(leaf(ChanceCondition.id, ChanceCondition.KEY_PERCENT to 35)),
                actionId = DelayAction.id,
                actionParams = Params.of(
                    DelayAction.KEY_SECONDS to 8,
                    DelayAction.KEY_MESSAGE to
                        "いま開くのが最善?\n" +
                        "この8秒は、あとで返ってくる。\n" +
                        "だいたいは、開かなくても困らない。",
                ),
            )
        },

        // ---- 開く癖を狙う ----------------------------------------------

        RulePreset(
            id = "quick_reopen",
            name = "閉じた直後の開き直しを止める",
            description = "5分以内に開き直したときだけ止める。1回目の用事は通す。",
            group = PresetGroup.TRIGGER,
            evidence = "起動トリガーで最も質が悪いのは目的の無い反射的な確認(Tran ら CHI 2019)。" +
                "「さっき見たばかり」はそれをいちばん素直に捉えられる合図。",
        ) { packages ->
            rule(
                name = "開き直しを止める",
                packages = packages,
                conditions = listOf(
                    leaf(QuickReopenCondition.id, QuickReopenCondition.KEY_WITHIN_MINUTES to 5),
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to
                        "さっき閉じたばかり。何か変わった?\n" +
                        "5分前に見たものが、いま増えている?\n" +
                        "手が勝手に動いた。それだけ。",
                    BlockAction.KEY_MIN_SECONDS to 10,
                    BlockAction.KEY_RELEASE_EFFORT to BlockAction.Effort.HOLD,
                ),
                consequence = Consequence(breakPoints = -5),
            )
        },

        RulePreset(
            id = "app_chain",
            name = "アプリの連鎖を断つ",
            description = "選んだアプリを使った直後だけ止める。「◯◯のあとつい」を名指しで狙う。",
            appPrompt = "この連鎖の**入口**になっているアプリを選んでください(通知を見たあとに開きがちなもの)",
            group = PresetGroup.TRIGGER,
            evidence = "セッションの82.94%は予測可能なパターンに収まり、望まない習慣の60%がSNS" +
                "(Monge Roffarello & De Russis, TiiS 2021)。連鎖の入口を切ると、下流がまとめて減る。",
        ) { packages ->
            // ここだけ packages の意味が逆。止める対象ではなく「直前のアプリ」。
            // 対象は作ったあとに自分で選ぶ
            Rule(
                name = "アプリの連鎖を断つ",
                target = Target(),
                condition = ConditionNode.AllOf(
                    listOf(
                        ConditionNode.Leaf(
                            AppChainCondition.id,
                            Params.of(
                                AppChainCondition.KEY_PACKAGES to packages.toList(),
                                AppChainCondition.KEY_FROM_HOME to false,
                            ),
                        ),
                    )
                ),
                actionId = DelayAction.id,
                actionParams = Params.of(
                    DelayAction.KEY_SECONDS to 6,
                    DelayAction.KEY_MESSAGE to
                        "さっきのアプリの流れで開いた。\n" +
                        "これは自分で決めて開いた?\n" +
                        "連鎖はここで切れる。",
                ),
            )
        },

        RulePreset(
            id = "night_precommit",
            name = "夜だけ先に決めておく",
            description = "22:00〜02:00 は問答無用で封印。その場では判断させない。",
            group = PresetGroup.TRIGGER,
            evidence = "夜間・低気分では「気づかせる」介入が効かない ── 眠いとリアクタンスは下がるが" +
                "行動も変わらない(Scrolling in the Deep 2025)。効かない時間帯は、事前に決めておくほうへ切り替える。",
        ) { packages ->
            rule(
                name = "夜は先に決めてある",
                packages = packages,
                conditions = listOf(
                    leaf(
                        TimeRangeCondition.id,
                        TimeRangeCondition.KEY_START to 22 * 60,
                        TimeRangeCondition.KEY_END to 2 * 60,
                    ),
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to
                        "この時間に開かないと、昼間の自分が決めた。\n" +
                        "眠いときの判断は、あてにならないと知っている。",
                    BlockAction.KEY_MIN_SECONDS to 20,
                    BlockAction.KEY_COVER_SYSTEM_BARS to true,
                    BlockAction.KEY_RELEASE_EFFORT to BlockAction.Effort.TYPE,
                ),
                consequence = Consequence(
                    lockScope = LockScope.APP,
                    lockMinutes = 30,
                    lockEscalates = true,
                ),
            )
        },

        // ---- 上限を決める ----------------------------------------------

        RulePreset(
            id = "night",
            name = "夜は開かない",
            description = "22:00〜06:00 のあいだ完全封印する。",
            group = PresetGroup.STRICT,
        ) { packages ->
            rule(
                name = "夜は開かない",
                packages = packages,
                conditions = listOf(
                    leaf(
                        TimeRangeCondition.id,
                        TimeRangeCondition.KEY_START to 22 * 60,
                        TimeRangeCondition.KEY_END to 6 * 60,
                    ),
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to "明日の自分から時間を借りようとしている。",
                    BlockAction.KEY_MIN_SECONDS to 20,
                    BlockAction.KEY_COVER_SYSTEM_BARS to true,
                ),
            )
        },

        RulePreset(
            id = "daily_budget",
            name = "1日30分まで",
            description = "毎日 4:00 起点で合計30分を超えたら封印する。",
            evidence = "上限を自分で決められることが受容の鍵(GoalKeeper)。" +
                "削減を望む人の平均希望削減率は34%(Allcott ら 2022)なので、いまの実績から3割減あたりが現実的。",
        ) { packages ->
            rule(
                name = "1日30分まで",
                packages = packages,
                conditions = listOf(
                    leaf(
                        TotalUsageCondition.id,
                        TotalUsageCondition.KEY_MINUTES to 30,
                        TotalUsageCondition.KEY_PERIOD to ResetPolicy(24 * 60, 4 * 60),
                    ),
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to "今日のぶんは使い切った。",
                    BlockAction.KEY_MIN_SECONDS to 15,
                ),
            )
        },

        RulePreset(
            id = "declare_first",
            name = "開く前に宣言する",
            description = "開くたびに「今回は何分使うか」を申告させる。使い切ったら封印。",
            evidence = "実行意図(「もし◯◯したら、◯◯する」)の効果量は d=0.65。" +
                "とくに**始めた作業からの脱線を防ぐ場面で最大(d=0.77)**。開く前に量を口に出させるのが、その一番安い形。",
        ) { packages ->
            rule(
                name = "開く前に宣言する",
                packages = packages,
                conditions = emptyList(),
                actionId = DeclareAction.id,
                actionParams = Params.of(
                    DeclareAction.KEY_MAX_MINUTES to 30,
                    DeclareAction.KEY_DEFAULT_MINUTES to 10,
                    DeclareAction.KEY_REQUIRE_REASON to false,
                    DeclareAction.KEY_REFLECTION to "宣言した時間は終わり。",
                ),
            )
        },

        RulePreset(
            id = "long_session",
            name = "だらだら見続けたら警告",
            description = "連続15分を超えたら、5分おきに警告を重ねる。操作は止めない。",
            group = PresetGroup.GENTLE,
            evidence = "「時間を無駄にした」という嫌悪感は約30分で自然に来る(Tran ら CHI 2019)。" +
                "その手前で声をかけると、自分でやめる判断が前倒しになる。",
        ) { packages ->
            rule(
                name = "だらだら見続けたら警告",
                packages = packages,
                conditions = listOf(
                    leaf(ContinuousUsageCondition.id, ContinuousUsageCondition.KEY_MINUTES to 15),
                ),
                actionId = WarnAction.id,
                actionParams = Params.of(
                    WarnAction.KEY_MESSAGE to "15分経った。まだ続ける?",
                    WarnAction.KEY_SECONDS to 6,
                    WarnAction.KEY_REPEAT_MINUTES to 5,
                ),
            )
        },

        RulePreset(
            id = "escalate",
            name = "効かなくなったら強くする",
            description = "ふだんは待たせるだけ。押し切りが続いたら封印に切り替え、繰り返すほど長く閉める。",
            group = PresetGroup.STRICT,
            evidence = "段階的ロック(1→5→15→30→60分)は -50.4分/日で選好52.8%。" +
                "いきなり強いロックは -73.7分だが選好13.9%で、20名が目標そのものを緩めた(GoalKeeper 2019)。" +
                "※これは重いほう。同じアプリに「軽いもの」も1つ入れて2段構えにしてください。",
        ) { packages ->
            rule(
                name = "慣れてきたら強くする",
                packages = packages,
                conditions = listOf(
                    leaf(HabituationCondition.id, HabituationCondition.KEY_OVERRIDES to 3),
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to
                        "軽いやり方では効かなくなった。\n" +
                        "押し切りが続いたので、強くしてある。\n" +
                        "ここまで来たのは自分の記録のせい。",
                    BlockAction.KEY_MIN_SECONDS to 25,
                    BlockAction.KEY_RELEASE_EFFORT to BlockAction.Effort.TYPE,
                ),
                consequence = Consequence(
                    lockScope = LockScope.APP,
                    lockMinutes = 5,
                    lockEscalates = true,
                    breakPoints = -20,
                ),
            )
        },

        RulePreset(
            id = "either_limit",
            name = "連続15分 または 合計1時間",
            description = "どちらか一方でも超えたら封印する。条件を「かつ」ではなく「または」で繋いだ例。",
            evidence = "20分超のセッション本数は、合計時間とは別の指標として効く。" +
                "グレースケール研究では開く回数は変わらず、長さだけが縮んで -20分/日になった。",
        ) { packages ->
            Rule(
                name = "連続15分 または 合計1時間",
                target = Target(packages = packages),
                condition = ConditionNode.AnyOf(
                    listOf(
                        leaf(ContinuousUsageCondition.id, ContinuousUsageCondition.KEY_MINUTES to 15),
                        leaf(
                            TotalUsageCondition.id,
                            TotalUsageCondition.KEY_MINUTES to 60,
                            TotalUsageCondition.KEY_PERIOD to ResetPolicy(24 * 60, 4 * 60),
                        ),
                    )
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to "どちらかの線を越えた。",
                    BlockAction.KEY_MIN_SECONDS to 15,
                ),
            )
        },

        RulePreset(
            id = "night_penalty",
            name = "夜に押し切ったらお預け",
            description = "22:00〜06:00 は封印。押し切ったら、そのアプリが30分ぶん開かなくなる。",
            group = PresetGroup.STRICT,
        ) { packages ->
            rule(
                name = "夜に押し切ったらお預け",
                packages = packages,
                conditions = listOf(
                    leaf(
                        TimeRangeCondition.id,
                        TimeRangeCondition.KEY_START to 22 * 60,
                        TimeRangeCondition.KEY_END to 6 * 60,
                    ),
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to "押し切れば、そのぶん後で閉まる。",
                    BlockAction.KEY_MIN_SECONDS to 20,
                ),
                consequence = Consequence(
                    lockScope = LockScope.APP,
                    lockMinutes = 30,
                ),
            )
        },

        RulePreset(
            id = "reopen",
            name = "開き直しすぎを止める",
            description = "1日に10回以上開いたら封印する。無意識に開く癖に効く。",
            group = PresetGroup.TRIGGER,
            evidence = "**測る指標と変えたい行動を揃える**と効果が跳ねる(行動→行動 d+=0.79、" +
                "行動→結果は 0.14 で有意差なし。Harkin ら 2016)。回数を減らしたいなら、時間ではなく回数で縛る。",
        ) { packages ->
            rule(
                name = "開き直しすぎを止める",
                packages = packages,
                conditions = listOf(
                    leaf(
                        SessionCountCondition.id,
                        SessionCountCondition.KEY_COUNT to 10,
                        SessionCountCondition.KEY_PERIOD to ResetPolicy(24 * 60, 4 * 60),
                    ),
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to "今日はもう10回開いている。",
                    BlockAction.KEY_MIN_SECONDS to 20,
                ),
            )
        },

        RulePreset(
            id = "work_hours",
            name = "平日の日中は封印",
            description = "月〜金の 9:00〜18:00 を完全封印する。",
            group = PresetGroup.STRICT,
        ) { packages ->
            rule(
                name = "平日の日中は封印",
                packages = packages,
                conditions = listOf(
                    leaf(DayOfWeekCondition.id, DayOfWeekCondition.KEY_DAYS to listOf(1, 2, 3, 4, 5)),
                    leaf(
                        TimeRangeCondition.id,
                        TimeRangeCondition.KEY_START to 9 * 60,
                        TimeRangeCondition.KEY_END to 18 * 60,
                    ),
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to "いまは手を動かす時間。",
                    BlockAction.KEY_MIN_SECONDS to 15,
                ),
            )
        },

        RulePreset(
            id = "calendar_focus",
            name = "予定が入っている間は封印",
            description = "カレンダーに「#集中」の予定があるあいだ封印する。予定を入れる行為が先約になる。",
            requiresCalendar = true,
        ) { packages ->
            rule(
                name = "#集中 の予定中は封印",
                packages = packages,
                conditions = listOf(
                    leaf(
                        CalendarBusyCondition.id,
                        CalendarBusyCondition.KEY_KEYWORD to "#集中",
                        CalendarBusyCondition.KEY_DURING_EVENT to true,
                    ),
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to "自分で予定に入れた時間。",
                    BlockAction.KEY_MIN_SECONDS to 25,
                ),
            )
        },

        RulePreset(
            id = "study_session",
            name = "学習中は必要なアプリだけ",
            description = "連携アプリが入れた学習予定のあいだ、ここで選んだアプリ以外をすべて封印する。" +
                "押し切りもできない。電話・ホーム・設定は選ばなくても必ず使える。",
            appPrompt = "学習中も使えるようにするアプリを選んでください(辞書・電卓・音楽など)",
            allowEmptyApps = true,
            group = PresetGroup.STRICT,
            evidence = "「そもそも誘惑のある状況に入らない」(状況選択)が自己制御では最も効率がよく、" +
                "意志で我慢する(反応調整)が最もコストが高く失敗しやすい(Duckworth ら 2016)。",
        ) { packages ->
            // ここだけ packages の意味が逆。止めるアプリではなく、残すアプリ。
            Rule(
                name = "学習中は必要なアプリだけ",
                target = Target(matchAll = true, exceptPackages = packages),
                condition = ConditionNode.AllOf(
                    listOf(
                        leaf(
                            StudySessionCondition.id,
                            StudySessionCondition.KEY_DURING_SESSION to true,
                        ),
                    )
                ),
                actionId = BlockAction.id,
                actionParams = Params.of(
                    BlockAction.KEY_REFLECTION to "いまは自分で予定に入れた勉強の時間。",
                    BlockAction.KEY_MIN_SECONDS to 20,
                    BlockAction.KEY_COVER_SYSTEM_BARS to true,
                    BlockAction.KEY_ALLOW_OVERRIDE to false,
                ),
            )
        },

        RulePreset(
            id = "study_prep",
            name = "予定の前に沈まない",
            description = "学習予定が始まる少し前から、選んだアプリに警告を重ねる。" +
                "操作は止めない。「気づいたら開始時刻を過ぎていた」を防ぐためのもの。",
            group = PresetGroup.GENTLE,
        ) { packages ->
            rule(
                name = "予定の前に沈まない",
                packages = packages,
                conditions = listOf(leaf(StudyPrepCondition.id)),
                actionId = WarnAction.id,
                actionParams = Params.of(
                    WarnAction.KEY_MESSAGE to "もうすぐ勉強の時間。そろそろ畳もう。",
                    WarnAction.KEY_SECONDS to 6,
                    WarnAction.KEY_REPEAT_MINUTES to 3,
                ),
            )
        },
    )

    private fun leaf(typeId: String, vararg params: Pair<String, Any?>): ConditionNode.Leaf =
        ConditionNode.Leaf(typeId, Params.of(*params))

    private fun rule(
        name: String,
        packages: Set<String>,
        conditions: List<ConditionNode.Leaf>,
        actionId: String,
        actionParams: Params,
        consequence: Consequence = Consequence.NONE,
    ): Rule = Rule(
        name = name,
        target = Target(packages = packages),
        condition = ConditionNode.AllOf(conditions),
        actionId = actionId,
        actionParams = actionParams,
        consequence = consequence,
    )
}
