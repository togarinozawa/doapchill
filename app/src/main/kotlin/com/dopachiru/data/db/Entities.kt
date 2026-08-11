package com.dopachiru.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ルール1件。
 *
 * 条件は種類ごとに列を持たせず JSON 1本で保持する。条件の種類が増えても
 * マイグレーションが要らないのが、この設計の一番の狙い。
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val enabled: Boolean = true,
    /** core の Target を JSON にしたもの。 */
    val targetJson: String,
    /** core の ConditionNode を JSON にしたもの。 */
    val conditionJson: String,
    val actionId: String,
    val actionParamsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** アプリに付けたタグ(= アプリグループ)。 */
@Entity(tableName = "app_tags", primaryKeys = ["packageName", "tag"])
data class AppTagEntity(
    val packageName: String,
    val tag: String,
)

/**
 * アプリを開いていた1区間。
 *
 * 開いている最中も endEpochSec を定期的に伸ばしていく。
 * null のまま置くとプロセスが強制終了されたときに区間が閉じられず、
 * 復帰時に「端末を触っていない時間」まで使用時間に化けるため。
 */
@Entity(
    tableName = "usage_sessions",
    indices = [Index("packageName"), Index("startEpochSec")],
)
data class UsageSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val startEpochSec: Long,
    /** 最後に「まだ開いている」と確認できた時刻。開始直後は開始時刻と同じ。 */
    val endEpochSec: Long,
)

/** 変更リクエスト。core の ChangeRequest に対応する。 */
@Entity(tableName = "change_requests")
data class ChangeRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val kind: String,
    val targetRuleId: Long?,
    val payloadJson: String,
    val previousJson: String,
    val reason: String,
    val createdAtEpochSec: Long,
    val resolvedAtEpochSec: Long?,
    val status: String,
    /** 通過済みゲートのキーをカンマ区切りで。 */
    val clearedGateKeys: String,
    /** このリクエストに課したゲートの一覧(JSON)。 */
    val gatesJson: String,
)

/**
 * 「開く前に宣言させる」で申告した持ち時間。
 * 1アプリにつき有効なものは高々1件。
 */
@Entity(tableName = "declarations", indices = [Index("packageName")])
data class DeclarationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val budgetMinutes: Int,
    val reason: String = "",
    val declaredAtEpochSec: Long,
    /** 消費済み秒数。フォアグラウンドにいるあいだ積まれる。 */
    val consumedSec: Long = 0L,
    val active: Boolean = true,
)

/**
 * ブロック画面を出した記録。
 * [insteadNote] に「代わりに何をしたか」を書き残せる。
 */
@Entity(tableName = "block_logs", indices = [Index("atEpochSec")])
data class BlockLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val ruleId: Long,
    val ruleName: String,
    val actionId: String,
    val atEpochSec: Long,
    /** ブロックされたあと何をしていたか。あとから書ける。 */
    val insteadNote: String = "",
    /** 押し切って使ってしまったか。 */
    val overridden: Boolean = false,
)

/**
 * 連携アプリから受け取った学習予定の窓。
 *
 * 終わりの時刻を自分で持たせているのが肝。「終わった」の連絡が来なくても
 * 時刻が過ぎれば勝手に解ける。送り主のアプリが落ちても閉じ込められない。
 *
 * [goalId] と [kind] はいまのドパチルは見ていない。将来「数学の予定のときだけ
 * 許す」ような使い方をするときに要るので、受け取ったものは捨てずに置いておく。
 */
@Entity(tableName = "study_windows", indices = [Index("startEpochSec")])
data class StudyWindowEntity(
    /** 送り主が付ける識別子。同じ id で送り直すと差し替わる。 */
    @PrimaryKey val id: String,
    val startEpochSec: Long,
    val endEpochSec: Long,
    val title: String = "",
    val goalId: String = "",
    val kind: String = "",
    val receivedAtEpochSec: Long,
)

/** 1日ぶんの集計。連続達成日数と育成要素の土台。 */
@Entity(tableName = "day_stats")
data class DayStatEntity(
    /** java.time.LocalDate.toEpochDay() */
    @PrimaryKey val epochDay: Long,
    val blockShownCount: Int = 0,
    val overrideCount: Int = 0,
    val totalScreenMinutes: Int = 0,
) {
    /** その日を「守れた日」とみなすか。押し切りが1度も無ければ守れた扱い。 */
    val kept: Boolean get() = overrideCount == 0
}
