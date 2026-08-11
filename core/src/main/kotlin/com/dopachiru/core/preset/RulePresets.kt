package com.dopachiru.core.preset

import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.types.CalendarBusyCondition
import com.dopachiru.core.condition.types.ContinuousUsageCondition
import com.dopachiru.core.condition.types.DayOfWeekCondition
import com.dopachiru.core.condition.types.SessionCountCondition
import com.dopachiru.core.condition.types.StudyPrepCondition
import com.dopachiru.core.condition.types.StudySessionCondition
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.condition.types.TotalUsageCondition
import com.dopachiru.core.model.ConditionNode
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
data class RulePreset(
    val id: String,
    val name: String,
    val description: String,
    /**
     * アプリを選ばせるときの見出し。
     * 許可リスト型の雛形では、選ぶのは「止めるアプリ」ではなく「残すアプリ」になる。
     */
    val appPrompt: String = "対象アプリを選んでください",
    /** 1つも選ばずに作れるか。許可リスト型だけ true。 */
    val allowEmptyApps: Boolean = false,
    private val builder: (Set<String>) -> Rule,
) {
    fun build(packages: Set<String>): Rule = builder(packages)
}

object RulePresets {

    val all: List<RulePreset> = listOf(
        RulePreset(
            id = "night",
            name = "夜は開かない",
            description = "22:00〜06:00 のあいだ完全封印する。",
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
            id = "reopen",
            name = "開き直しすぎを止める",
            description = "1日に10回以上開いたら封印する。無意識に開く癖に効く。",
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
    ): Rule = Rule(
        name = name,
        target = Target(packages = packages),
        condition = ConditionNode.AllOf(conditions),
        actionId = actionId,
        actionParams = actionParams,
    )
}
