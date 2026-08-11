package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/** 常に成立する。完全封印を組むときに使う。 */
object AlwaysCondition : ConditionType {
    override val id = "always"
    override val displayName = "常に"
    override val description = "条件なし。対象アプリを常に制限する(完全封印)。"

    override val params: List<ParamSpec> = emptyList()

    override fun evaluate(p: Params, ctx: EvalContext): Boolean = true

    override fun summarize(p: Params): String = "常に"

    /** 変わらない。 */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime = ctx.now.plusDays(1)
}
