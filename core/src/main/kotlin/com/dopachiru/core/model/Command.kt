package com.dopachiru.core.model

import com.dopachiru.core.gate.Gate
import com.dopachiru.core.gate.GatePolicy
import com.dopachiru.core.param.Params
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 別の端末に頼む1つの操作。
 *
 * ## なぜ「命令」ではなく「頼みごと」なのか
 *
 * 送った側は**実行できません**。できるのは積むことだけで、実際にやるかどうかは
 * 受け取った端末が決めます。ここを逆にすると、スマホが PC の親玉になり、
 * **PC の閉め出しはスマホを持っているだけで全部無効になります**。
 * 閉め出しの意味は「いま解けないこと」なので、解ける口を1つ足せば全部消えます。
 *
 * ## 締めるのは素通し、緩めるのは関門を通す
 *
 * [CommandKind.loosens] が真のものは、受け取った端末の関門([Gate])を
 * 通すまで実行されません。しかも**待ち時間は受け取った端末が見たときから**
 * 数えます([acceptedAtSec]) ── 送信時刻から数えると、端末の時計を戻すだけで
 * クールダウンを飛ばせるからです。
 *
 * 関門のうち、時間で開くもの(クールダウン・時間帯)は遠隔でもそのまま通ります。
 * 理由を書かせるものは、送るときに書いた文([reason])がそのまま通ります。
 * **パスワードとミニゲームだけは遠隔で通せません** ── 相手の端末の秘密と
 * 手を使うものなので、最後はその端末の前でやることになります。
 *
 * ## 取りこぼしても事故にならないように
 *
 * 期限([expiresAtSec])を過ぎたものは実行しません。PC を3日ぶりに起動した瞬間に
 * 「いま30分閉め出す」が走るのは、頼んだときの意図とは別物です。
 */
@Serializable
data class Command(
    /** 端末をまたいで一意な ID。同期の鍵。 */
    val uid: String,

    /** 誰に頼むか(deviceId)。 */
    val to: String,

    /** 誰が頼んだか(deviceId)。画面に出すためと、自分の頼みを自分で実行しないため。 */
    val from: String,

    /** [CommandKind] のどれか。 */
    val kind: String,

    val params: Params = Params.EMPTY,

    /** 緩める頼みのときに書いた理由。相手の「理由を書く」関門はこれで通る。 */
    val reason: String = "",

    val issuedAtSec: Long = 0L,

    /** これを過ぎたら実行しない。 */
    val expiresAtSec: Long = 0L,

    val state: String = CommandState.PENDING,

    /**
     * 受け取った端末が最初にこれを見た時刻。クールダウンの起点。
     *
     * 送信時刻を起点にしないのは、送る側の時計を戻すだけで待ちを飛ばせるから。
     */
    val acceptedAtSec: Long = 0L,

    /** 受け取った端末で通した関門。 */
    val clearedGateKeys: Set<String> = emptySet(),

    /** 何が起きたか。送った端末の画面に出す。 */
    val note: String = "",

    val handledAtSec: Long = 0L,
) {
    val loosens: Boolean get() = CommandKind.loosens(kind)

    fun isExpiredAt(nowSec: Long): Boolean = expiresAtSec in 1 until nowSec

    val isOpen: Boolean
        get() = state == CommandState.PENDING || state == CommandState.ACCEPTED
}

/** 頼める操作。 */
object CommandKind {
    /** 集中を始める。`minutes`。 */
    const val FOCUS_START = "focus_start"

    /** いま閉め出す。`minutes`。 */
    const val LOCK_NOW = "lock_now"

    /** ルールを有効にする。`ruleUid`。 */
    const val RULE_ENABLE = "rule_enable"

    /** ルールを止める。`ruleUid`。**緩める**。 */
    const val RULE_DISABLE = "rule_disable"

    /** 閉まっているものを開ける(罰・集中)。**緩める**。 */
    const val UNLOCK = "unlock"

    /** 解禁券を使う。`minutes`。**緩める**。 */
    const val PASS = "pass"

    const val KEY_MINUTES = "minutes"
    const val KEY_RULE_UID = "ruleUid"

    /** 送り先の端末の縛りを弱めるか。真なら関門を通さないと実行しない。 */
    fun loosens(kind: String): Boolean = kind == RULE_DISABLE || kind == UNLOCK || kind == PASS

    fun label(kind: String): String = when (kind) {
        FOCUS_START -> "集中を始める"
        LOCK_NOW -> "いま閉め出す"
        RULE_ENABLE -> "ルールを有効にする"
        RULE_DISABLE -> "ルールを止める"
        UNLOCK -> "閉まっているものを開ける"
        PASS -> "解禁券を使う"
        else -> kind
    }
}

object CommandState {
    /** まだ相手が見ていない。 */
    const val PENDING = "pending"

    /** 相手が受け取った。関門待ち。 */
    const val ACCEPTED = "accepted"

    /** 実行した。 */
    const val DONE = "done"

    /** 実行しなかった(相手が断った・対象が見つからない)。 */
    const val REFUSED = "refused"

    /** 期限切れ。 */
    const val EXPIRED = "expired"

    /** 送った側が取り下げた。 */
    const val CANCELLED = "cancelled"

    fun label(state: String): String = when (state) {
        PENDING -> "届けています"
        ACCEPTED -> "相手が受け取りました"
        DONE -> "済み"
        REFUSED -> "断られました"
        EXPIRED -> "期限切れ"
        CANCELLED -> "取り下げ"
        else -> state
    }
}

/** 頼みごとの決まりごと。端末側の保存方法に依存しないようここに置く。 */
object Commands {

    /** 締める頼みの既定の期限。取りこぼしたものが何時間も後に走らないように短い。 */
    const val TIGHTEN_TTL_SEC = 30L * 60

    /** 緩める頼みの既定の期限。関門を通すのに時間がかかるので長い。 */
    const val LOOSEN_TTL_SEC = 24L * 60 * 60

    /** 遠隔では通せない関門。相手の端末の秘密と手を使うもの。 */
    fun needsTargetDevice(gate: Gate): Boolean =
        gate is Gate.Password || gate is Gate.MiniGame

    /**
     * まだ通っていない関門。
     *
     * 締める頼みには関門を課しません ── 自分を縛るほうに摩擦を足す理由が無く、
     * 足せば「縛るのが面倒」になって使わなくなります。
     *
     * @param acceptedAtSec 受け取った端末が最初に見た時刻。0 ならいまとして数える。
     */
    fun remainingGates(
        command: Command,
        gates: List<Gate>,
        nowSec: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Gate> {
        if (!command.loosens) return emptyList()

        // 理由を書かせる関門は、送るときに書いた文でそのまま通る
        val cleared = command.clearedGateKeys.toMutableSet()
        val writeReason = gates.filterIsInstance<Gate.WriteReason>().firstOrNull()
        if (writeReason != null && command.reason.trim().length >= writeReason.minLength) {
            cleared += writeReason.key
        }

        val from = command.acceptedAtSec.takeIf { it > 0 } ?: nowSec
        return GatePolicy.remaining(
            gates = gates,
            clearedKeys = cleared,
            createdAt = at(from, zone),
            now = at(nowSec, zone),
        )
    }

    /**
     * 届いた1件を、受け取った端末がどう扱うか。
     *
     * **判断はここに1つだけ置きます。** Android と Windows で別々に書くと、
     * かたや関門を通し、かたや素通し、のような食い違いが必ず出ます。
     * 実行そのもの(集中を始める・閉め出す)は端末ごとのやり方なので、
     * ここでは「やってよいか」だけを答えます。
     *
     * @param myDeviceId 受け取った端末の deviceId。
     * @param gates その端末の関門。
     */
    fun triage(
        command: Command,
        myDeviceId: String,
        gates: List<Gate>,
        nowSec: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CommandVerdict {
        // 自分宛てでないものには触らない。書き戻すと、本来の宛先の答えを踏む
        if (command.to != myDeviceId) return CommandVerdict.Ignore
        if (!command.isOpen) return CommandVerdict.Ignore

        // 自分が出した頼みを自分で実行しない。無限に往復する
        if (command.from == myDeviceId) {
            return CommandVerdict.Drop(CommandState.REFUSED, "自分宛ての頼みです")
        }

        if (command.isExpiredAt(nowSec)) {
            return CommandVerdict.Drop(CommandState.EXPIRED, "期限を過ぎました")
        }

        val remaining = remainingGates(command, gates, nowSec, zone)
        if (remaining.isEmpty()) return CommandVerdict.Run

        return CommandVerdict.Wait(remaining)
    }

    private fun at(epochSec: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), zone)
}

/** [Commands.triage] の答え。 */
sealed interface CommandVerdict {
    /** 実行してよい。 */
    data object Run : CommandVerdict

    /** まだ関門が残っている。受け取ったことだけ書き戻して待つ。 */
    data class Wait(val gates: List<Gate>) : CommandVerdict {
        /** 画面に出す一言。 */
        fun describe(): String = gates.joinToString("、") { it.describe() }

        /** その端末の前でしか通せない関門が混じっているか。 */
        val needsTargetDevice: Boolean get() = gates.any { Commands.needsTargetDevice(it) }
    }

    /** 実行しない。[state] を書き戻して終わり。 */
    data class Drop(val state: String, val note: String) : CommandVerdict

    /** 自分に関係がない。触らない。 */
    data object Ignore : CommandVerdict
}
