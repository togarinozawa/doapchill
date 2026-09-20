package com.dopachiru.core.action

import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * ルールが成立したときに起こすこと。
 *
 * 条件と同じく、実装を1つ書いて [BuiltInActions] に足せば設定画面に出る。
 * 実際の画面表示は app 側の BlockPresenter が [id] を見て振り分ける。
 */
interface ActionType {
    /** 永続化に使う不変のID。 */
    val id: String

    val displayName: String

    val description: String

    val params: List<ParamSpec>

    /**
     * 強さ。複数のルールが同時に成立したとき、この値が最大のものが採用される。
     * 警告(10) < 宣言要求(50) < 完全封印(100) の順。
     */
    /**
     * ほかの措置と**同時に出しておけるか**。
     *
     * 画面を覆うもの(閉じる・待たせる・音だけ)は1つしか出せません ──
     * 覆いの裏に隠れて見えないので、重ねても嘘になる。覆わない覚え書き
     * (経過表示・目的のチップ)だけが重なります。
     *
     * 重ねたものは**主の措置とは別に**、成立している組ぜんぶから集めます。
     */
    val stackable: Boolean get() = false

    val severity: Int

    fun summarize(p: Params): String
}
