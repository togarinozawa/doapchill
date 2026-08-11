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
    val severity: Int

    fun summarize(p: Params): String
}
