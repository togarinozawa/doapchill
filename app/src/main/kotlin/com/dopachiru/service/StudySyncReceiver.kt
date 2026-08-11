package com.dopachiru.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dopachiru.data.StudyWindowRepository
import com.dopachiru.runtime.DopaRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 連携アプリ(勉強予定を組むアプリなど)から、今日の学習予定を受け取る口。
 *
 * ## 送り方
 * ```kotlin
 * val intent = Intent(StudySync.ACTION_SYNC).apply {
 *     setPackage("com.dopachiru")          // これが無いと届かない
 *     putExtra(StudySync.EXTRA_PAYLOAD, json)
 * }
 * sendBroadcast(intent)
 * ```
 *
 * `setPackage` は必須。Android 8 以降、宛先を指定しないブロードキャストは
 * マニフェスト登録の受信機に届かない。
 *
 * 送り主のマニフェストに `<uses-permission android:name="com.dopachiru.permission.SYNC_STUDY"/>`
 * が要る。この権限は signature 保護なので、同じ鍵で署名したアプリしか名乗れない。
 *
 * ## 中身
 * ```json
 * {
 *   "version": 1,
 *   "windows": [
 *     { "id": "sched_8842", "startAt": 1786000000, "endAt": 1786003600,
 *       "title": "数学 演習", "goalId": "goal_17", "kind": "study" }
 *   ]
 * }
 * ```
 * `startAt` / `endAt` は UTC のエポック秒。`title` 以降は省略可。
 *
 * ## 全置換であることの意味
 * 届いた配列が、そのまま今の全予定になる。差分ではない。
 *  - 予定が増えた / ずれた → 新しい一覧を送り直す
 *  - **早く終わったので今すぐ解除したい → その窓の endAt を今の時刻にして送り直す**
 *
 * 解除専用の経路を作っていないのは、経路が2本あると「終了だけ届かなかった」
 * 状態が生まれるため。全置換なら、届いた最後の1通が常に正しい。
 *
 * そして窓は終わりの時刻を自分で持っているので、送り主が落ちて何も届かなくなっても
 * 時刻が来れば勝手に解ける。閉じ込められる方向には壊れない。
 */
object StudySync {
    const val ACTION_SYNC = "com.dopachiru.action.SYNC_STUDY_WINDOWS"
    const val EXTRA_PAYLOAD = "windows"
    const val PERMISSION = "com.dopachiru.permission.SYNC_STUDY"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Payload(
        val version: Int = 1,
        val windows: List<Window> = emptyList(),
    )

    @Serializable
    private data class Window(
        val id: String = "",
        val startAt: Long = 0L,
        val endAt: Long = 0L,
        val title: String = "",
        val goalId: String = "",
        val kind: String = "",
    )

    /** 壊れた JSON が来ても落とさない。読めなければ null を返して何もしない。 */
    fun parse(payload: String): List<StudyWindowRepository.Window>? = runCatching {
        json.decodeFromString(Payload.serializer(), payload).windows.map {
            StudyWindowRepository.Window(
                id = it.id,
                startSec = it.startAt,
                endSec = it.endAt,
                title = it.title,
                goalId = it.goalId,
                kind = it.kind,
            )
        }
    }.getOrNull()
}

/**
 * 予定を受け取ってから、判定をやり直させるまで。
 *
 * マニフェスト登録にしてあるのは、ユーザー補助が一時的に落ちていても
 * 取りこぼさないため。受け取った時点で Room に入るので、次にサービスが
 * 立ち上がったときには学習中のまま復元される。
 */
class StudySyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StudySync.ACTION_SYNC) return
        val payload = intent.getStringExtra(StudySync.EXTRA_PAYLOAD) ?: return
        val windows = StudySync.parse(payload) ?: return

        DopaRuntime.init(context)
        DopaRuntime.studyWindows.replaceAll(windows)

        // 学習開始の瞬間、そのアプリはもう前面にいる。画面切り替えのイベントは
        // 来ないので、ここで明示的に見直させないと最大30秒待たされる。
        DopaAccessibilityService.kickEvaluation()
    }
}
