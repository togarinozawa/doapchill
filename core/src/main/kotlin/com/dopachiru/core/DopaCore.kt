package com.dopachiru.core

import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.condition.types.AlwaysCondition
import com.dopachiru.core.condition.types.CalendarBusyCondition
import com.dopachiru.core.condition.types.ContinuousUsageCondition
import com.dopachiru.core.condition.types.DayOfWeekCondition
import com.dopachiru.core.condition.types.DeclaredBudgetCondition
import com.dopachiru.core.condition.types.PowerSaveCondition
import com.dopachiru.core.condition.types.SessionCountCondition
import com.dopachiru.core.condition.types.StudyPrepCondition
import com.dopachiru.core.condition.types.StudySessionCondition
import com.dopachiru.core.condition.types.TimeRangeCondition
import com.dopachiru.core.condition.types.TotalUsageCondition
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import kotlinx.serialization.json.Json

/**
 * 組み込みの条件・アクションをレジストリに載せる。
 *
 * ## 条件を1つ増やす手順
 *  1. `condition/types/` に [com.dopachiru.core.condition.ConditionType] の実装を1ファイル作る
 *  2. 下の [registerAll] の一覧に足す
 *
 * これだけで設定画面の選択肢に出て、パラメータの入力欄も生える。
 * app モジュールには一切手を入れない。
 */
object DopaCore {

    /** Rule / ConditionNode を保存・復元するときの JSON 設定。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    private var registered = false

    fun registerAll() {
        if (registered) return
        registered = true

        ConditionRegistry.register(
            AlwaysCondition,
            TimeRangeCondition,
            DayOfWeekCondition,
            ContinuousUsageCondition,
            SessionCountCondition,
            TotalUsageCondition,
            DeclaredBudgetCondition,
            CalendarBusyCondition,
            PowerSaveCondition,
            StudySessionCondition,
            StudyPrepCondition,
        )

        ActionRegistry.register(
            BlockAction,
            WarnAction,
            DeclareAction,
        )
    }

    fun encodeRule(rule: Rule): String = json.encodeToString(Rule.serializer(), rule)

    fun decodeRule(text: String): Rule = json.decodeFromString(Rule.serializer(), text)

    fun encodeCondition(node: ConditionNode): String =
        json.encodeToString(ConditionNode.serializer(), node)

    fun decodeCondition(text: String): ConditionNode =
        json.decodeFromString(ConditionNode.serializer(), text)
}
