package com.dopachiru.core.gate

import kotlinx.serialization.Serializable

/** 変更リクエストの種類。 */
enum class ChangeKind {
    CREATE,
    UPDATE,
    DELETE,
    ENABLE,
    DISABLE,
}

/** リクエストの状態。 */
enum class ChangeStatus {
    /** ゲート通過待ち。 */
    PENDING,

    /** 適用済み。 */
    APPLIED,

    /** 自分で取り下げた。 */
    CANCELLED,
}

/**
 * ルール変更の起票。
 *
 * 変更を即時反映せず一度ここに積むことで、
 *  - 衝動的な緩和にクールダウンを挟める
 *  - 「何を、なぜ変えたか」が履歴として残り、あとで振り返れる
 * の2つを同時に満たす。
 */
@Serializable
data class ChangeRequest(
    val id: Long = 0L,
    val kind: ChangeKind,

    /** 対象ルール。CREATE のときは null。 */
    val targetRuleId: Long? = null,

    /** 適用する Rule のスナップショット(JSON)。DELETE のときは空。 */
    val payloadJson: String = "",

    /** 変更前の Rule のスナップショット(JSON)。振り返り用。CREATE のときは空。 */
    val previousJson: String = "",

    /** なぜ変えたいのか。WriteReason ゲートで書かせた文。 */
    val reason: String = "",

    val createdAtEpochSeconds: Long,
    val resolvedAtEpochSeconds: Long? = null,
    val status: ChangeStatus = ChangeStatus.PENDING,

    /** 通過済みゲートのキー。 */
    val clearedGateKeys: Set<String> = emptySet(),
)
