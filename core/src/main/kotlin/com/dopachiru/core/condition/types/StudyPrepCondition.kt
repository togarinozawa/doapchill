package com.dopachiru.core.condition.types

import com.dopachiru.core.condition.ConditionType
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import java.time.LocalDateTime

/**
 * 学習予定が始まる手前の「助走枠」に成立する。
 *
 * 予定の時間帯だけ塞いでも、実際の失敗は取り逃がす。
 *
 * > 夕飯後9時とかまでスマホをいじっちゃって、そのころに危機感沸いて慌てて始める
 *
 * 21:00 開始の予定を守らせても、20:00 が無防備なら予定ごと潰れる。
 * ここは**始まってから縛るのではなく、始まる前に捕まえる**ための条件。
 *
 * 助走枠の長さはドパチル側の設定で決める(連携元は今までどおり予定だけ送る)。
 * 強く縛るより、気づかせるほうが目的に合う ── 沈んでいることに気づけば戻れる。
 */
object StudyPrepCondition : ConditionType {

    override val id = "study_prep"
    override val displayName = "学習予定の直前"
    override val description = "次の学習予定が始まる少し前のあいだ成立する。長さは設定で変えられる。"

    override val params = emptyList<ParamSpec>()

    override fun evaluate(p: Params, ctx: EvalContext): Boolean = ctx.study.inPrep

    override fun nextChangeAt(p: Params, ctx: EvalContext): LocalDateTime? =
        ctx.study.nextBoundaryAfter(ctx.now)

    override fun summarize(p: Params): String = "学習予定の直前"
}
