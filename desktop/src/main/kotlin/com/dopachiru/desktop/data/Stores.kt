package com.dopachiru.desktop.data

import com.dopachiru.core.gate.ChangeRequest
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.model.Command
import com.dopachiru.core.model.FocusSettings
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.sync.DeviceInfo
import com.dopachiru.core.sync.SyncSettings
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.Rule
import com.dopachiru.core.points.PointEvent
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.desktop.platform.BlockStrength
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Windows 版だけの設定。ルールそのものは端末で共通なので、ここには入れない。 */
@Serializable
data class DesktopSettings(
    /** ブロックのやり方。Windows には Android のような統一された止め方が無いので選ばせる。 */
    val blockStrength: BlockStrength = BlockStrength.MINIMIZE,

    /** 一時停止中。トレイから切り替える。 */
    val paused: Boolean = false,

    /** Windows と一緒に起動する。 */
    val launchAtLogin: Boolean = false,

    /** 押し切ったあと、何分そのアプリを見逃すか。 */
    val overrideGraceMinutes: Int = 5,

    /**
     * ポイントの使い道と相場。
     *
     * Android と別に持つ。同期でルールが渡ってきても、
     * 「押し切りに代金を取るか」は端末ごとに決めたいことがあるため。
     */
    val pointPolicy: PointPolicy = PointPolicy.DEFAULT,

    /** 解禁券で制限が止まっている期限(秒)。過ぎれば勝手に戻る。 */
    val passUntilSec: Long = 0L,

    /**
     * ルール変更を通しにくくするための関門。**同期しません**(端末ごと)。
     *
     * 空なら変更は即時反映。1つでもあると、ルールの作成・変更・削除は
     * いったん申請になり、全部通るまで効きません ── これが無いと、
     * 開きたくなった瞬間にルールを消せてしまい、縛りが縛りになりません。
     */
    val gates: List<Gate> = emptyList(),

    /** ブラウザ拡張からの URL 受け口を開けるか。 */
    val bridgeEnabled: Boolean = true,

    /** 集中モードの既定値。Android と同じ形なので、いずれ同期に載せられる。 */
    val focus: FocusSettings = FocusSettings(),

    /** 端末間の同期。既定では切ってある。 */
    val sync: SyncSettings = SyncSettings(),

    /**
     * 名簿に出すこの端末の名前。空なら deviceId がそのまま出る。
     *
     * deviceId と分けてあるのは、**deviceId を変えると実績の見出しが切れる**ため。
     * 呼び名を変えたいだけのときに過去の記録を捨てさせない。
     */
    val deviceName: String = "",

    /**
     * 開発者向けの操作を出すか。既定は出さない。
     *
     * ここに隠すのは**縛りを丸ごと無効にできるもの**だけ ── 一時停止と、
     * プロセスの一時停止([BlockStrength.SUSPEND])。
     *
     * 隠す理由は、手の届くところに全部切れるスイッチがあると、
     * 詰まったときにまずそれを押してしまうから。実際、
     * 「オーバーレイが出ない」の正体は SUSPEND を選んだままだったこと。
     * 押せる場所にあるだけで、意図せず選ばれる。
     */
    val developerMode: Boolean = false,

    /** 予約をいまから何分先からしか取れないか。直前予約を封じる待ち。 */
    val reservationLeadMinutes: Int = com.dopachiru.core.model.ReservationRules.MIN_LEAD_MINUTES,

    /**
     * 拡張と分け合う合言葉。
     *
     * 空なら「まだ繋いでいない」。設定から「つなぐ」を押した2分のあいだだけ
     * 配られるので、ここが埋まっている = 一度は自分の手で繋いだ、という意味になる。
     */
    val bridgeToken: String = "",
)

@Serializable
data class RuleFile(
    val rules: List<Rule> = emptyList(),
    /** 次に振る ID。Room の autoGenerate にあたるもの。 */
    val nextId: Long = 1L,
    /** プロセス名 → タグ。 */
    val tags: Map<String, Set<String>> = emptyMap(),

    /**
     * 関門待ちのルール変更。**同期しません**(端末ごと)。
     *
     * 承認待ちが別の端末に流れると、片方で出した申請をもう片方で承認できてしまい、
     * 関門が意味を失います。送るものは [DesktopSync] が明示的に選んでいるので、
     * ここに置いても勝手には出ていきません。
     */
    val changeRequests: List<ChangeRequest> = emptyList(),
    val nextChangeId: Long = 1L,

    /**
     * 他の端末の名簿。同期のたびに入れ替わる。
     *
     * 自分の行もここに入れる ── 名前を付け替えたときに送る元が要るのと、
     * 画面で「この端末」を他と同じ形で見せるため。
     */
    val devices: List<DeviceInfo> = emptyList(),

    /**
     * 端末をまたいだ頼みごと。出したものと、受け取ったものの両方。
     *
     * 済んだものも少しのあいだ残す ── 送った側の画面に「済み」を出すため。
     * 掃除は [DesktopSync] が期限で落とす。
     */
    val commands: List<Command> = emptyList(),

    /**
     * 同期の覚え書き。Android の `sync_state` 表にあたるもの。
     *
     * 鍵は `種類|uid`。**消したことを覚える場所**がここで、無いとルールを消しても
     * 次の同期で別の端末から送り返されて生き返ります。
     * タグと名札は行に時刻を持たないので、変えた時刻もここに置きます。
     */
    val syncState: Map<String, SyncStamp> = emptyMap(),
) {
    fun stampOf(kind: String, uid: String): SyncStamp? = syncState["$kind|$uid"]

    fun withStamp(kind: String, uid: String, stamp: SyncStamp): RuleFile =
        copy(syncState = syncState + ("$kind|$uid" to stamp))
}

@Serializable
data class SyncStamp(val updatedAt: Long, val deleted: Boolean = false)

@Serializable
data class UsageSession(
    val processName: String,
    val startSec: Long,
    val endSec: Long,
)

@Serializable
data class Declaration(
    val processName: String,
    val budgetMinutes: Int,
    val reason: String = "",
    val declaredAtSec: Long,
    val consumedSec: Long = 0L,
)

object Stores {
    val settings = JsonStore("settings.json", DesktopSettings.serializer()) { DesktopSettings() }
    val rules = JsonStore("rules.json", RuleFile.serializer()) { RuleFile() }
    val usage = JsonStore("usage.json", ListSerializer(UsageSession.serializer())) { emptyList() }
    val declarations =
        JsonStore("declarations.json", ListSerializer(Declaration.serializer())) { emptyList() }

    /**
     * 罰で閉まっているもの。
     *
     * ファイルに落とすのは、再起動で罰が消えては罰にならないため。
     * 逆に期限を過ぎれば勝手に解けるので、閉じ込め続けることもない。
     */
    val lockouts = JsonStore("lockouts.json", ListSerializer(Lockout.serializer())) { emptyList() }

    /** ポイントの増減。残高はこの合計。 */
    val points = JsonStore("points.json", ListSerializer(PointEvent.serializer())) { emptyList() }

    /** 予約。冷静なうちに取った「この時間だけ使う」枠。 */
    val reservations =
        JsonStore("reservations.json", ListSerializer(Reservation.serializer())) { emptyList() }
}
