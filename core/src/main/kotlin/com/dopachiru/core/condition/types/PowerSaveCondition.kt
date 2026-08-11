package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/**
 * 端末が省電力モードのときに成立する。
 *
 * 電池が減っているときほどスマホを触る余裕は無いはずで、
 * 「省電力モードに入れる」という自分の操作を、そのまま制限を強める合図として使える。
 */
object PowerSaveCondition : ConditionType {
    const val KEY_WHILE_SAVING = "whileSaving"

    override val id = "power_save"
    override val displayName = "省電力モード"
    override val description = "端末が省電力モードのあいだ(または解除されているあいだ)に成立する。"

    override val params = listOf(
        ParamSpec.BoolParam(
            KEY_WHILE_SAVING,
            "省電力モード中に成立させる",
            default = true,
            help = "オフにすると、省電力モードでないあいだに成立する",
        ),
    )

    override fun evaluate(p: Params, ctx: EvalContext): Boolean =
        ctx.powerSaveMode == p.bool(KEY_WHILE_SAVING, true)

    override fun summarize(p: Params): String =
        if (p.bool(KEY_WHILE_SAVING, true)) "省電力モード中" else "省電力モードでないあいだ"

    /** 切り替わりはシステムのブロードキャストで拾えるので、時間で見に来る必要がない。 */
    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime = ctx.now.plusDays(1)
}
