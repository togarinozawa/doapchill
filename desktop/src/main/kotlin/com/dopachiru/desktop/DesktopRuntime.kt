package com.dopachiru.desktop

import com.dopachiru.core.DopaCore
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.gate.ChangeRequest
import com.dopachiru.core.gate.ChangeStatus
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.gate.GatePolicy
import com.dopachiru.core.action.Rotation
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.TimerAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.io.ImportPlan
import com.dopachiru.core.io.RuleBundleIo
import com.dopachiru.core.action.types.IntentionAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.RadioAction
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.Focus
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Lockouts
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.Reservations
import com.dopachiru.core.model.Rule
import com.dopachiru.core.points.PointEvent
import com.dopachiru.core.points.PointReason
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.desktop.bridge.LocalBridge
import com.dopachiru.desktop.data.DeclarationTracker
import com.dopachiru.desktop.data.DesktopSettings
import com.dopachiru.desktop.data.RuleFile
import com.dopachiru.desktop.data.DesktopSync
import com.dopachiru.core.sync.UsageDay
import com.dopachiru.desktop.data.SyncStamp
import com.dopachiru.core.sync.SyncKinds
import com.dopachiru.desktop.data.Stores
import com.dopachiru.desktop.data.UsageLedger
import com.dopachiru.desktop.platform.BlockStrength
import com.dopachiru.desktop.platform.Browsers
import com.dopachiru.desktop.platform.ForegroundApp
import com.dopachiru.desktop.platform.ForegroundWatcher
import com.dopachiru.desktop.platform.ProtectedProcesses
import com.dopachiru.desktop.platform.WindowsAutoStart
import com.dopachiru.desktop.platform.WindowControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** いま画面に出すべきもの。 */
sealed interface Presentation {
    val key: String

    data class Block(
        override val key: String,
        val processName: String,
        val label: String,
        val ruleName: String,
        val reflection: String,
        val minSeconds: Int,
        val allowOverride: Boolean,
        /** 同じ文が続かないよう回している旨の説明。回していなければ空。 */
        val rotationNote: String = "",
        /** 押し切るのに要る手間。 */
        val releaseEffort: String = BlockAction.Effort.TAP,
        /** 押し切るのに要るポイント。0 なら代金を取らない。 */
        val overrideCost: Int = 0,
        val balance: Int = 0,
        /** 押し切ったら何が閉まるか。押す前に見せる。 */
        val penaltyNote: String = "",
    ) : Presentation {
        val canAfford: Boolean get() = overrideCost <= 0 || balance >= overrideCost
    }

    /**
     * ルールを破った罰で閉まっている。
     * 押し切る手立ては無い。あるのは残り時間だけ。
     */
    data class Locked(
        override val key: String,
        val processName: String,
        val label: String,
        val reason: String,
        val untilEpochSec: Long,
        /**
         * 自分で始めた集中か。罰なら false。
         *
         * 同じ封鎖の仕組みで動くが、画面に出す約束が正反対になる ──
         * 罰は「押し切る手段はありません」、集中は「足せる・切り上げられる」。
         */
        val isFocus: Boolean = false,
        /** いま切り上げるのに要るポイント。0 なら無料。 */
        val abortCost: Int = 0,
        val balance: Int = 0,
    ) : Presentation

    /** 数秒待たせて必ず通す。押し切りボタンは無い。 */
    data class Delay(
        override val key: String,
        val label: String,
        val message: String,
        val seconds: Int,
        val rotationNote: String,
    ) : Presentation

    /** 経過時間だけを隅に出す。操作は止めない。 */
    data class Timer(
        override val key: String,
        val minutes: Int,
        val todayMinutes: Int?,
    ) : Presentation

    data class Warn(override val key: String, val message: String) : Presentation

    data class Declare(
        override val key: String,
        val processName: String,
        val label: String,
        val maxMinutes: Int,
        val defaultMinutes: Int,
        val requireReason: Boolean,
    ) : Presentation

    /**
     * 映像を覆って音だけ残す。完全封印と違って**最小化も一時停止もしない** ──
     * 覆うだけなので、下のアプリは音を鳴らし続ける。
     */
    data class Radio(
        override val key: String,
        val label: String,
        val message: String,
        val peekEffort: String,
        val peekSeconds: Int,
    ) : Presentation

    /** 目的を書かせる入力。書いたら [Intention] の札に替わる。 */
    data class IntentionInput(
        override val key: String,
        val processName: String,
        val label: String,
        val prompt: String,
        val suggestions: List<String>,
    ) : Presentation

    /** 書いた目的を隅に出し続ける札。操作は止めない。 */
    data class Intention(
        override val key: String,
        val text: String,
        val minutes: Int?,
    ) : Presentation
}

/**
 * Windows 版の中枢。
 *
 * 判定そのものは Android とまったく同じ [RuleEngine] を通す。ここがやるのは
 * 「前面のアプリを Win32 から拾う」「判定の結果を Windows のやり方で実行する」
 * の2つだけ。
 */
object DesktopRuntime {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = RuleEngine()

    private val ledger = UsageLedger()
    private val declarations = DeclarationTracker()

    private val _ruleFile = MutableStateFlow(RuleFile())
    val ruleFile: StateFlow<RuleFile> = _ruleFile.asStateFlow()

    private val _settings = MutableStateFlow(DesktopSettings())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    private val _foreground = MutableStateFlow<ForegroundApp?>(null)
    val foreground: StateFlow<ForegroundApp?> = _foreground.asStateFlow()

    private val _presentation = MutableStateFlow<Presentation?>(null)
    val presentation: StateFlow<Presentation?> = _presentation.asStateFlow()

    /** いま押さえているアプリ。オーバーレイが前面に出ても見失わないように持っておく。 */
    private var heldApp: ForegroundApp? = null

    /** 押し切られたアプリを、しばらく再ブロックしないための猶予。 */
    private val overrideUntil = HashMap<String, Long>()

    /**
     * 「わかった、やめる」を押されたアプリの猶予。
     *
     * Android には「ホームに戻す」という確実な逃がし先があるが、Windows には無い。
     * 最小化してもフォーカスが戻ってくることがあり、そのまま判定すると
     * **閉じた瞬間にまた塞がれて、画面から出られなくなる**。
     * 数秒だけ見逃して、離れる隙を作る。
     */
    private val dismissedUntil = HashMap<String, Long>()

    /** ラジオを覗いているあいだ、覆い直さないための期限。プロセスごと。 */
    private val radioPeekUntil = HashMap<String, Long>()

    /** その回に書いた「何をしに開いた」。キーは プロセス|セッション種。 */
    private val intentions = HashMap<String, String>()

    /** 同じブロックを出した回数。暴走を検知して自動で止めるため。 */
    private val blockShownTimes = ArrayDeque<Long>()

    /** 警告を最後に出した時刻。ルールIDごと。 */
    private val warnShownAt = HashMap<Long, Long>()

    /**
     * 警告を出したあと「無視した」とみなす時刻。ルールIDごと。
     *
     * 警告には押し切りボタンが無いので、居座り続けること自体が押し切りにあたる。
     * ここが無いと、いちばん弱い措置だけ罰の外に置かれることになる。
     */
    private val ignoreDeadline = HashMap<Long, Long>()

    /**
     * 警告を無視した罰を科し終えた組み合わせ。そのアプリを離れたら消える。
     *
     * 警告は成立しているあいだ繰り返し出るので、出るたびに罰していると
     * 1時間ほどで残高が下限に張り付き、そこから何をしても押し切れなくなる。
     * 「無視して使い続けた」は**その一続きにつき1回**と数える。
     */
    private val punishedWarnIgnores = HashSet<String>()

    /** そっと知らせ(閉じる前の予告)を出し終えた / 数えている最中の一続き。 */
    private val prewarnDone = HashSet<String>()
    private val prewarnPending = HashSet<String>()

    /**
     * ルールごとの押し切り回数。慣れの判定に使う。
     * Android は記録テーブルから引くが、こちらは押し切った時点で数える。
     */
    private val overrideCounts = HashMap<Long, Int>()

    /** 待ち時間を通したセッション。同じ使用のあいだ出し直さないため。 */
    private val passedDelays = HashSet<String>()

    /** 段階的な封鎖のために、直近に科した記録を覚えておく。 */
    private val imposedLog = ArrayList<Pair<String, Long>>()

    /**
     * 宣言超過の罰を科し終えた組み合わせ。
     *
     * 超過中は毎秒ここを通るので、印を付けないと罰が積み上がる。
     */
    private val punishedOverruns = HashSet<String>()

    private val _lockouts = MutableStateFlow<List<Lockout>>(emptyList())
    val lockouts: StateFlow<List<Lockout>> = _lockouts.asStateFlow()

    private val _reservations = MutableStateFlow<List<Reservation>>(emptyList())
    /** 予約。冷静なうちに取った「この時間だけ使う」枠。 */
    val reservations: StateFlow<List<Reservation>> = _reservations.asStateFlow()

    private val _points = MutableStateFlow<List<PointEvent>>(emptyList())
    val points: StateFlow<List<PointEvent>> = _points.asStateFlow()

    private val _balance = MutableStateFlow(0)
    val balance: StateFlow<Int> = _balance.asStateFlow()

    private val ownPid: Long = ProcessHandle.current().pid()

    // ---- ブラウザ拡張との橋 ------------------------------------------

    /**
     * 拡張が最後に報せてきた URL と、その時刻。
     *
     * 時刻を持つのは、**拡張が黙ったときに開けるため**。URL だけを覚えていると、
     * 拡張が落ちた瞬間のページで判定が凍りつき、別のページに移っても
     * 塞がったままになる。古い報せは無かったことにして通す。
     */
    @Volatile
    private var browserUrl: String? = null

    @Volatile
    private var browserUrlAtMs: Long = 0L

    private val bridge = LocalBridge(
        onUrl = ::onBrowserUrl,
        tokenStore = object : LocalBridge.TokenStore {
            override fun current(): String = _settings.value.bridgeToken
            override fun save(token: String) = updateSettings { it.copy(bridgeToken = token) }
        },
    )

    /** 拡張とのつながり具合。設定画面に出す。 */
    data class BridgeStatus(
        val running: Boolean = false,
        val port: Int = 0,
        val paired: Boolean = false,
        val pairing: Boolean = false,
        /** 最後に拡張から話しかけられてからの秒数。一度も無ければ null。 */
        val lastSeenSecAgo: Long? = null,
    )

    private val _bridgeStatus = MutableStateFlow(BridgeStatus())
    val bridgeStatus: StateFlow<BridgeStatus> = _bridgeStatus.asStateFlow()

    /**
     * 拡張から「いまこの URL を見ている」と報せが来たとき。
     *
     * ここで評価まで済ませて、いま画面に出ているものをそのまま返す。
     * 返り値は拡張がタブを退避させるためのもの ── 本体の全画面は音を止められないので、
     * 動画が裏で鳴り続けるのを拡張側に止めてもらう必要がある。
     */
    @Synchronized
    private fun onBrowserUrl(url: String?): LocalBridge.Verdict {
        browserUrl = url
        browserUrlAtMs = System.currentTimeMillis()

        val fg = _foreground.value ?: return LocalBridge.Verdict()
        // 前面がブラウザでなければ、URL は判定に関わらない
        if (fg.processName !in Browsers) return LocalBridge.Verdict()

        evaluate(fg)

        return when (val p = _presentation.value) {
            is Presentation.Block -> LocalBridge.Verdict(true, p.ruleName)
            is Presentation.Locked -> LocalBridge.Verdict(true, p.reason)
            is Presentation.Delay -> LocalBridge.Verdict(true, "少し待つ")
            else -> LocalBridge.Verdict()
        }
    }

    /** 設定から「つなぐ」を押したとき。2分だけ合言葉の窓が開く。 */
    fun startPairing() {
        if (!bridge.isRunning) bridge.start()
        bridge.openPairing()
        refreshBridgeStatus()
    }

    /** 拡張との縁を切る。合言葉を捨てるので、次は繋ぎ直しになる。 */
    fun unpairBridge() {
        updateSettings { it.copy(bridgeToken = "") }
        browserUrl = null
        refreshBridgeStatus()
    }

    fun setBridgeEnabled(enabled: Boolean) {
        updateSettings { it.copy(bridgeEnabled = enabled) }
        if (enabled) bridge.start() else bridge.stop()
        if (!enabled) browserUrl = null
        refreshBridgeStatus()
    }

    private fun refreshBridgeStatus() {
        _bridgeStatus.value = BridgeStatus(
            running = bridge.isRunning,
            port = bridge.port,
            paired = _settings.value.bridgeToken.isNotBlank(),
            pairing = bridge.isPairing,
            lastSeenSecAgo = bridge.lastSeenAtMs
                .takeIf { it > 0L }
                ?.let { (System.currentTimeMillis() - it) / 1000 },
        )
    }

    fun start() {
        DopaCore.registerAll()
        _settings.value = Stores.settings.load()
        _ruleFile.value = Stores.rules.load()
        ledger.restore(Stores.usage.load())
        declarations.restore(Stores.declarations.load())
        // 罰と残高も戻す。再起動で罰が消えるなら罰にならない
        _lockouts.value = Lockouts.prune(Stores.lockouts.load(), nowSec())
        _reservations.value = Reservations.prune(Stores.reservations.load(), nowSec())
        _points.value = Stores.points.load()
        _balance.value = _points.value.sumOf { it.delta }

        if (_settings.value.bridgeEnabled) bridge.start()
        refreshBridgeStatus()

        // 入れ直して置き場所が変わっていても、ここで登録し直される。
        // 「入れたはずなのに立ち上がらない」の大半がこれ
        WindowsAutoStart.reconcile(_settings.value.launchAtLogin)

        scope.launch { watchLoop() }
        scope.launch { persistLoop() }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    // ------------------------------------------------------------------

    /**
     * Windows と一緒に立ち上げるかを切り替える。
     *
     * 設定にも書くが、効いているのはレジストリのほう。設定に残すのは
     * 入れ直しでパスが変わったときに書き直すため([WindowsAutoStart.reconcile])。
     *
     * @return いま実際に登録されているか。インストール版でなければ常に false。
     */
    fun setLaunchAtLogin(enabled: Boolean): Boolean {
        val actual = WindowsAutoStart.reconcile(enabled)
        updateSettings { it.copy(launchAtLogin = actual) }
        return actual
    }

    fun updateSettings(transform: (DesktopSettings) -> DesktopSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        Stores.settings.save(updated)
        if (updated.paused) releaseHold()
    }

    fun updateRules(transform: (RuleFile) -> RuleFile) {
        val updated = transform(_ruleFile.value)
        _ruleFile.value = updated
        Stores.rules.save(updated)
    }

    /**
     * ルールを触ったことを覚えておく。
     *
     * Android は行に updatedAt があるが、こちらは JSON なので自分で押す。
     * 押し忘れると、変えたのに古いままの時刻で送られて**相手に負ける**。
     */
    private fun RuleFile.stamped(uid: String, deleted: Boolean = false): RuleFile =
        if (uid.isBlank()) this else withStamp(SyncKinds.RULES, uid, SyncStamp(nowSec(), deleted))

    fun addRule(rule: Rule) = updateRules { file ->
        // uid は端末をまたいで一意。id と違って、作った端末が変わっても付いて回る
        val uid = rule.uid.ifBlank { java.util.UUID.randomUUID().toString() }
        file.copy(
            rules = file.rules + rule.copy(id = file.nextId, uid = uid),
            nextId = file.nextId + 1,
        ).stamped(uid)
    }

    /**
     * ルールを消す。
     *
     * 消したことを墓標に残します。残さないと、次の同期で別の端末が
     * 送り返してきて生き返ります。
     */
    fun removeRule(id: Long) = updateRules { file ->
        val uid = file.rules.firstOrNull { it.id == id }?.uid.orEmpty()
        file.copy(rules = file.rules.filter { r -> r.id != id }).stamped(uid, deleted = true)
    }

    // ---- 関門つきのルール変更 ---------------------------------------------

    /**
     * ルールの作成・変更・削除を申し込む。
     *
     * 関門が1つも無ければその場で反映する。1つでもあれば申請として積み、
     * 全部通るまで効かない ── **開きたくなった瞬間に消せる縛りは縛りではない**。
     *
     * @return 申請として積んだら true。即時反映したら false。
     */
    fun requestChange(kind: ChangeKind, rule: Rule): Boolean {
        val gates = _settings.value.gates
        if (gates.isEmpty()) {
            applyChangeNow(kind, rule)
            return false
        }
        updateRules { file ->
            file.copy(
                changeRequests = file.changeRequests + ChangeRequest(
                    id = file.nextChangeId,
                    kind = kind,
                    targetRuleId = rule.id.takeIf { it != 0L },
                    payloadJson = if (kind == ChangeKind.DELETE) {
                        ""
                    } else {
                        DopaCore.json.encodeToString(Rule.serializer(), rule)
                    },
                    previousJson = file.rules.firstOrNull { it.id == rule.id }
                        ?.let { DopaCore.json.encodeToString(Rule.serializer(), it) }
                        .orEmpty(),
                    createdAtEpochSeconds = nowSec(),
                ),
                nextChangeId = file.nextChangeId + 1,
            )
        }
        return true
    }

    /** 申請を実際にルールへ落とす。関門を通ったあと、または関門が無いときだけ。 */
    private fun applyChangeNow(kind: ChangeKind, rule: Rule) {
        when (kind) {
            ChangeKind.CREATE -> addRule(rule)
            ChangeKind.DELETE -> removeRule(rule.id)
            ChangeKind.UPDATE, ChangeKind.ENABLE, ChangeKind.DISABLE -> updateRules { file ->
                file.copy(rules = file.rules.map { if (it.id == rule.id) rule else it })
                    .stamped(rule.uid)
            }
        }
    }

    /** その申請にまだ残っている関門。空なら通せる。 */
    fun remainingGates(request: ChangeRequest): List<Gate> = GatePolicy.remaining(
        gates = _settings.value.gates,
        clearedKeys = request.clearedGateKeys,
        createdAt = LocalDateTime.ofEpochSecond(request.createdAtEpochSeconds, 0, zoneOffset()),
        now = LocalDateTime.now(),
    )

    /** 手を動かして通す種類の関門(理由を書く、など)を通過済みにする。 */
    fun clearGate(requestId: Long, key: String, reason: String = "") = updateRules { file ->
        file.copy(
            changeRequests = file.changeRequests.map { request ->
                if (request.id != requestId) {
                    request
                } else {
                    request.copy(
                        clearedGateKeys = request.clearedGateKeys + key,
                        reason = reason.ifBlank { request.reason },
                    )
                }
            }
        )
    }

    /** 申請を取り下げる。 */
    fun cancelChange(requestId: Long) = updateRules { file ->
        file.copy(changeRequests = file.changeRequests.filterNot { it.id == requestId })
    }

    /**
     * 通った申請をルールに落とす。定期処理から呼ぶ。
     *
     * 待つだけで通る関門(クールダウン・時間帯)があるので、押した瞬間だけでなく
     * 時間の経過でも見に来ないと、通っているのに適用されないまま止まる。
     */
    fun applyReadyChanges() {
        val pending = _ruleFile.value.changeRequests.filter { it.status == ChangeStatus.PENDING }
        if (pending.isEmpty()) return
        val ready = pending.filter { remainingGates(it).isEmpty() }
        if (ready.isEmpty()) return

        ready.forEach { request ->
            val rule = if (request.kind == ChangeKind.DELETE) {
                _ruleFile.value.rules.firstOrNull { it.id == request.targetRuleId }
            } else {
                runCatching {
                    DopaCore.json.decodeFromString(Rule.serializer(), request.payloadJson)
                }.getOrNull()
            }
            if (rule != null) applyChangeNow(request.kind, rule)
        }
        updateRules { file ->
            file.copy(changeRequests = file.changeRequests.filterNot { done -> ready.any { it.id == done.id } })
        }
    }

    private fun zoneOffset(): java.time.ZoneOffset =
        java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now())

    // ---- タグ ------------------------------------------------------------

    /** いま在るタグの名前。どの端末で付けたものでも、降りてきていれば出ます。 */
    fun knownTags(): List<String> =
        _ruleFile.value.tags.values.flatten().distinct().sorted()

    /**
     * そのプロセスに付いているタグを丸ごと入れ替える。
     *
     * 時刻を押すのを忘れると、変えたのに古いままの時刻で送られて**相手に負けます**。
     * タグは行に時刻を持たないので、[RuleFile.syncState] のほうに押します。
     */
    fun setTagsFor(process: String, tags: Set<String>) = updateRules { file ->
        val next = if (tags.isEmpty()) file.tags - process else file.tags + (process to tags)
        file.copy(tags = next)
            .withStamp(
                SyncKinds.TAGS,
                "${DesktopSync.PLATFORM}:$process",
                SyncStamp(nowSec(), deleted = tags.isEmpty()),
            )
    }

    /** そのプロセスのタグを1つ付け外しする。 */
    fun toggleTag(process: String, tag: String) {
        val current = _ruleFile.value.tags[process] ?: emptySet()
        setTagsFor(process, if (tag in current) current - tag else current + tag)
    }

    /**
     * タグの名前を、付いている全プロセスから消す。
     *
     * ルールがそのタグを指していても消します ── 指し先が無いタグを残すより、
     * 「何にも当たらないルール」として編集画面で気づけるほうがよい。
     */
    fun deleteTag(tag: String) {
        _ruleFile.value.tags
            .filterValues { tag in it }
            .keys
            .toList()
            .forEach { process -> setTagsFor(process, (_ruleFile.value.tags[process] ?: emptySet()) - tag) }
    }

    // ---- 端末間の同期 --------------------------------------------------

    /**
     * 1往復。**別スレッドで呼ぶこと** ── 通信を待つあいだ画面が固まります。
     *
     * 実績は日ごとの記録から組み立てます。Windows 側は1日の合計だけ持っているので、
     * アプリごとの内訳は載せません(Android 側が載せます)。
     */
    fun syncNow(): DesktopSync.Outcome {
        // Windows 側は今日ぶんだけ送ります。使用の記録を48時間しか持っていないので、
        // 何日ぶんも送りようがない ── **持っていないものを 0 として送ると、
        // サーバー上の過去の記録を 0 で塗り潰します。**
        val today = LocalDate.now()
        val minutes = ledger.totalMinutesIn(ResetPolicy())
        val usage = if (minutes <= 0) {
            emptyList()
        } else {
            listOf(
                UsageDay(
                    date = today.toString(),
                    totalMinutes = minutes,
                    perApp = ledger.breakdownIn(ResetPolicy()).toMap(),
                ),
            )
        }
        return DesktopSync.run(
            file = _ruleFile.value,
            settings = _settings.value.sync,
            usage = usage,
            onApply = { updated ->
                _ruleFile.value = updated
                Stores.rules.save(updated)
            },
            onSettings = { next -> updateSettings { it.copy(sync = next) } },
        )
    }

    // ---- 持ち出しと取り込み --------------------------------------------

    /** いまのルールを1つの JSON にする。条件やアクションの目録も添える。 */
    fun exportRules(): String {
        val file = _ruleFile.value
        return RuleBundleIo.export(
            rules = file.rules,
            tags = file.tags,
            exportedAt = LocalDateTime.now().toString(),
        )
    }

    /** 読んだだけ。**まだ何も変えない。** */
    fun planImport(text: String): Result<ImportPlan> =
        when (val parsed = RuleBundleIo.parse(text)) {
            is RuleBundleIo.ParseResult.Failed -> Result.failure(IllegalArgumentException(parsed.message))
            is RuleBundleIo.ParseResult.Ok ->
                Result.success(RuleBundleIo.plan(parsed.bundle, _ruleFile.value.rules))
        }

    /** 見せた計画をそのまま実行する。ここで初めて中身が変わる。 */
    fun applyImport(plan: ImportPlan) = updateRules { file ->
        var nextId = file.nextId
        val added = plan.added.map { rule -> rule.copy(id = nextId++) }
        val replacedByUid = plan.replaced.associate { (before, after) -> before.uid to after }
        file.copy(
            rules = file.rules.map { replacedByUid[it.uid] ?: it } + added,
            nextId = nextId,
            tags = file.tags + plan.tags,
        )
    }

    /** ルールを1つ差し替える。条件・罰の編集から使う。 */
    fun updateRule(rule: Rule) = updateRules { file ->
        file.copy(rules = file.rules.map { if (it.id == rule.id) rule else it }).stamped(rule.uid)
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) = updateRules { file ->
        val uid = file.rules.firstOrNull { it.id == id }?.uid.orEmpty()
        file.copy(rules = file.rules.map { if (it.id == id) it.copy(enabled = enabled) else it })
            .stamped(uid)
    }

    fun todayBreakdown(): List<Pair<String, Int>> = ledger.breakdownIn(ResetPolicy())

    fun todayTotalMinutes(): Int = ledger.totalMinutesIn(ResetPolicy())

    // ------------------------------------------------------------------

    /** ブロック画面の「わかった、やめる」。押さえていたアプリを引っ込める。 */
    fun dismissBlock() {
        val held = heldApp
        if (_presentation.value is Presentation.Block) heldRule?.let { reward(it) }
        _presentation.value = null
        releaseHold()
        if (held != null) {
            dismissedUntil[held.processName] = System.currentTimeMillis() + DISMISS_GRACE_MS
            WindowControl.minimize(held.hwnd)
            WindowControl.focusDesktop()
        }
        // 覚えている前面は、閉じたアプリのまま。次の巡回で読み直させる
        _foreground.value = null
    }

    /**
     * 逃げ道。ブロック画面で Esc を長押しすると通る。
     *
     * 全画面で最前面に出ている以上、こちらの不具合で閉じられなくなったときに
     * ユーザーが自力で抜ける手段が必ず要る。抑止のために出しているものが
     * 端末を人質に取ってはいけない。
     */
    fun emergencyPause() {
        _presentation.value = null
        releaseHold()
        WindowControl.resumeAll()
        _foreground.value = null
        updateSettings { it.copy(paused = true) }
    }

    /** ブロック画面の「それでも使う」。 */
    fun overrideBlock() {
        val held = heldApp ?: return
        val block = _presentation.value as? Presentation.Block
        if (block != null && !block.canAfford) return

        // 罰を先に科してから猶予を置く。順番が逆だと、
        // 罰で閉まる前に猶予が効いて素通りになる
        heldRule?.let {
            overrideCounts[it.id] = (overrideCounts[it.id] ?: 0) + 1
            punish(held.processName, it, PointReason.OVERRIDE)
        }
        overrideUntil[held.processName] =
            System.currentTimeMillis() + _settings.value.overrideGraceMinutes * 60_000L
        _presentation.value = null
        releaseHold()
    }

    // ------------------------------------------------------------------
    // 破った / 守ったときに起きること

    /** ブロックを出したときのルール。押し切り・引き返しの相手を覚えておく。 */
    @Volatile
    private var heldRule: Rule? = null

    /**
     * ルールを破った。罰を科し、ポイントを引く。
     *
     * 封鎖は罰を科した時点の範囲で固定する。あとからルールを書き換えても
     * 罰の重さが変わらないようにするため。
     */
    private fun punish(processName: String, rule: Rule, reason: PointReason) {
        val consequence = rule.consequence
        val policy = _settings.value.pointPolicy

        consequence.resolveTarget(processName, rule.target)?.let { target ->
            impose(consequence, target, rule.name, rule.name)
        }

        if (policy.enabled) {
            addPoints(policy.breakDelta(consequence.breakPoints), reason, rule.name)
        }
    }

    /**
     * 封鎖を科す。破った罰にも、時間切れの閉め出しにも使う。
     *
     * 範囲は科した時点で固定する。あとからルールを書き換えても重さが変わらない。
     *
     * @param ruleName 段階を数える鍵。同じルールで繰り返したときだけ伸びる。
     * @param notice 封鎖画面に出す言葉。
     */
    private fun impose(
        consequence: Consequence,
        target: com.dopachiru.core.model.Target,
        ruleName: String,
        notice: String,
    ): Lockout? {
        val now = nowSec()
        // 段階を切ってあれば、直近24時間に同じルールで科した回数だけ長くなる
        imposedLog.removeAll { (_, at) -> at < now - ESCALATION_WINDOW_SEC }
        val repeats = if (consequence.lockEscalates) {
            imposedLog.count { (name, _) -> name == ruleName }
        } else {
            0
        }
        val minutes = consequence.lockMinutesFor(repeats)
        if (minutes <= 0) return null
        imposedLog.add(ruleName to now)

        val lockout = Lockout(
            // uid が無いと、同時に複数走っているとき互いを見分けられない
            uid = java.util.UUID.randomUUID().toString(),
            target = target,
            untilEpochSec = now + minutes * 60L,
            reason = notice,
            createdAtEpochSec = now,
        )
        _lockouts.value = _lockouts.value + lockout
        Stores.lockouts.save(_lockouts.value)
        return lockout
    }

    /**
     * 時間切れで閉め出す。破ったからではなく、取り決めどおりに閉まる。
     *
     * ポイントは動かさない ── 違反ではないため。
     */
    private fun lockOut(fg: ForegroundApp, act: Decision.Act) {
        val consequence = LockoutAction.consequenceOf(act.params)
        val target = consequence.resolveTarget(fg.processName, act.rule.target) ?: return
        val notice = act.params.string(LockoutAction.KEY_NOTICE).ifBlank { act.rule.name }
        val lockout = impose(consequence, target, act.rule.name, notice) ?: return
        showLocked(fg, lockout)
    }

    /** ブロック画面から引き返した。 */
    private fun reward(rule: Rule) {
        val policy = _settings.value.pointPolicy
        if (!policy.enabled) return
        addPoints(policy.keepDelta(rule.consequence.keepPoints), PointReason.BACKED_OFF, rule.name)
    }

    /**
     * ポイントを動かす。
     *
     * 下限に当たっているぶんは差し引く。際限なくマイナスに沈むと、
     * そこから何をしても押し切れないまま「もうどうにでもなれ」に振り切ってしまう。
     */
    fun addPoints(delta: Int, reason: PointReason, note: String = "") {
        if (delta == 0) return
        val floor = _settings.value.pointPolicy.floor
        val effective = if (delta < 0) {
            val room = _balance.value - floor
            if (room <= 0) return
            maxOf(delta, -room)
        } else {
            delta
        }
        if (effective == 0) return

        _points.value = (_points.value + PointEvent(
            delta = effective,
            reason = reason,
            note = note,
            atEpochSec = nowSec(),
        )).takeLast(POINT_HISTORY_LIMIT)
        _balance.value += effective
        Stores.points.save(_points.value)
    }

    /**
     * 解禁券を買う。買えたら true。
     *
     * 期限を持たせてあるので、買ったまま解除を忘れて縛りが死ぬことがない。
     */
    fun buyPass(): Boolean {
        val policy = _settings.value.pointPolicy
        if (!policy.enabled || !policy.passEnabled) return false
        if (_balance.value < policy.passCost) return false

        addPoints(-policy.passCost, PointReason.PASS_BOUGHT, "${policy.passMinutes}分")
        updateSettings { it.copy(passUntilSec = nowSec() + policy.passMinutes * 60L) }
        // 買った瞬間に効かせる。次の巡回まで塞がれたままでは券にならない
        _presentation.value = null
        releaseHold()
        return true
    }

    /** 解禁券が効いているあいだの期限(秒)。効いていなければ 0。 */
    // ---- 自分で始める集中 ------------------------------------------------

    /** いま走っている集中。罰は含まない。 */
    fun activeFocus(): Lockout? = Focus.activeIn(_lockouts.value, nowSec())

    /**
     * 集中を始める。すでに走っていれば何もしない。
     *
     * 止める仕組みは罰と同じ封鎖。違うのは出口があることだけ。
     */
    fun startFocus(minutes: Int = _settings.value.focus.defaultMinutes): Boolean {
        if (activeFocus() != null) return false
        val policy = _settings.value.pointPolicy
        val focus = Focus.start(
            nowSec = nowSec(),
            minutes = minutes,
            allowPackages = _settings.value.focus.allowPackages,
            allowTags = _settings.value.focus.allowTags,
            effort = _settings.value.focus.abortEffort,
            abortPoints = if (policy.enabled) policy.focusAbortCost else 0,
        )
        _lockouts.value = _lockouts.value + focus
        Stores.lockouts.save(_lockouts.value)
        evaluate(_foreground.value)
        return true
    }

    /**
     * 範囲を指定して集中を始める。範囲以外は [startFocus] と同じ。
     *
     * 「このグループだけ」「このグループ以外」「全部」を選んで止められる。
     * ホーム画面のショートカットは Windows には無いので、始めるのは設定画面から。
     */
    fun startFocus(template: com.dopachiru.core.model.FocusTemplate, minutes: Int): Boolean {
        if (activeFocus() != null || !template.isUsable) return false
        val policy = _settings.value.pointPolicy
        val focus = Focus.startWithTarget(
            nowSec = nowSec(),
            minutes = minutes,
            target = template.target(),
            effort = _settings.value.focus.abortEffort,
            abortPoints = if (policy.enabled) policy.focusAbortCost else 0,
            label = template.displayLabel(),
        )
        _lockouts.value = _lockouts.value + focus
        Stores.lockouts.save(_lockouts.value)
        evaluate(_foreground.value)
        return true
    }

    fun extendFocus(addMinutes: Int): Boolean {
        val now = nowSec()
        val focus = activeFocus() ?: return false
        val longer = Focus.extend(focus, addMinutes, now)
        _lockouts.value = _lockouts.value.map { if (it.uid == focus.uid) longer else it }
        Stores.lockouts.save(_lockouts.value)
        evaluate(_foreground.value)
        return true
    }

    /**
     * 集中を時間より前に終わらせる。
     *
     * 猶予のうちは無料。それ以降はポイントを払う ── 払えなければ終われない。
     * 手間(長押しなど)は画面側で先に通してある。
     */
    fun endFocusEarly(): Boolean {
        val now = nowSec()
        val focus = activeFocus() ?: return false
        val cost = if (focus.canCancelFreelyAt(now)) 0 else (focus.earlyExit?.points ?: 0)
        if (cost > 0 && _balance.value < cost) return false

        _lockouts.value = _lockouts.value.filterNot { it.uid == focus.uid && it.isChosen }
        Stores.lockouts.save(_lockouts.value)
        if (cost > 0) {
            addPoints(-cost, PointReason.FOCUS_ABORTED, "残り${focus.remainingMinutesAt(now)}分")
        }
        _presentation.value = null
        releaseHold()
        evaluate(_foreground.value)
        return true
    }

    // ---- 予約 ----------------------------------------------------------

    /**
     * 予約を1つ取る。開始が [minLeadMinutes] より手前なら弾く(直前予約は封じる)。
     * @return 取れたら予約。弾いたら null。
     */
    fun book(
        target: com.dopachiru.core.model.Target,
        startEpochSec: Long,
        endEpochSec: Long,
        minLeadMinutes: Int,
        note: String = "",
    ): Reservation? {
        val now = nowSec()
        if (startEpochSec < now + minLeadMinutes * 60L) return null
        if (endEpochSec <= startEpochSec) return null
        val reservation = Reservation(
            uid = java.util.UUID.randomUUID().toString(),
            target = target,
            startEpochSec = startEpochSec,
            endEpochSec = endEpochSec,
            note = note,
        )
        val next = Reservations.prune(_reservations.value, now) + reservation
        _reservations.value = next
        Stores.reservations.save(next)
        return reservation
    }

    /** 予約を取り消す。 */
    fun cancelReservation(uid: String) {
        val next = _reservations.value.filterNot { it.uid == uid }
        _reservations.value = next
        Stores.reservations.save(next)
    }

    // ---- ラジオ・目的(画面から呼ばれる) --------------------------------

    /** ラジオを覗く。覆いをどけ、[seconds] 秒たったら覆い直す。 */
    fun peekRadio(seconds: Int) {
        val fg = _foreground.value ?: return
        radioPeekUntil[fg.processName] = System.currentTimeMillis() + seconds * 1000L
        _presentation.value = null
        releaseHold()
    }

    /** 「何をしに開いた」を書いた。その回のあいだ覚えておき、札に替える。 */
    fun setIntention(text: String) {
        val fg = _foreground.value ?: return
        if (text.isNotBlank()) intentions["${fg.processName}|${ledger.currentSessionSeed()}"] = text
        _presentation.value = null
        releaseHold()
        evaluate(fg)
    }

    /** 走り切った集中に加点する。掃除のついでに見る。 */
    private fun awardFinishedFocus(finished: List<Lockout>) {
        val policy = _settings.value.pointPolicy
        if (!policy.enabled || policy.focusDonePoints == 0) return
        finished.filter { it.isChosen }.forEach { focus ->
            if (awardedFocusUids.add(focus.uid)) {
                val minutes = ((focus.untilEpochSec - focus.createdAtEpochSec) / 60).toInt()
                addPoints(policy.focusDonePoints, PointReason.FOCUS_DONE, "${minutes}分")
            }
        }
    }

    /** 加点済みの集中。二重に足さないための覚え書き。 */
    private val awardedFocusUids = HashSet<String>()

    fun passUntil(): Long = _settings.value.passUntilSec.takeIf { nowSec() < it } ?: 0L

    /** 開発・確認用。罰を手で解く経路はここだけ。 */
    fun clearLockouts() {
        _lockouts.value = emptyList()
        Stores.lockouts.save(emptyList())
    }

    /** 待ち時間が終わった。同じ使用のあいだは出し直さない。 */
    fun passDelay() {
        val key = _presentation.value?.key ?: return
        passedDelays.add(key)
        _presentation.value = null
        releaseHold()
    }

    fun declare(processName: String, minutes: Int, reason: String) {
        declarations.declare(processName, minutes, reason)
        _presentation.value = null
    }

    fun cancelDeclare() {
        val held = heldApp
        _presentation.value = null
        releaseHold()
        if (held != null) WindowControl.minimize(held.hwnd)
    }

    /** 一時停止・再開に使う。止めていたプロセスを必ず戻す。 */
    private fun releaseHold() {
        val held = heldApp
        if (held != null && WindowControl.isSuspended(held.pid)) WindowControl.resume(held.pid)
        heldApp = null
        heldRule = null
    }

    // ------------------------------------------------------------------

    private suspend fun watchLoop() {
        var lastProcess: String? = null

        while (scope.isActive) {
            val seen = ForegroundWatcher.current()

            // 自分のオーバーレイが前面に出ているあいだは、前面のアプリは変わっていない扱い。
            // ここを素直に見ると、ブロック画面を出した瞬間に対象から外れて即座に解除され、
            // 出す→消えるを繰り返す。
            val fg = if (seen != null && seen.pid.toLong() == ownPid) _foreground.value else seen

            val nowSec = System.currentTimeMillis() / 1000
            val processName = fg?.processName

            if (processName != lastProcess) {
                ledger.onForegroundChanged(processName, nowSec)
                // アプリを離れた = 一続きの終わり。無視の印を落として数え直す
                punishedWarnIgnores.removeAll { it.endsWith("|$lastProcess") }
                ignoreDeadline.clear()
                // 予告の途中で離れたら取りやめ。次に開いたらまた予告から
                prewarnPending.clear()
                prewarnDone.clear()
                lastProcess = processName
            } else {
                ledger.tick(nowSec)
            }
            declarations.tick(processName, nowSec)
            _foreground.value = fg

            if (_settings.value.paused) {
                if (_presentation.value != null) {
                    _presentation.value = null
                    releaseHold()
                }
            } else {
                evaluate(fg)
                enforce()
            }

            delay(POLL_MS)
        }
    }

    private suspend fun persistLoop() {
        while (scope.isActive) {
            delay(PERSIST_MS)
            Stores.usage.save(ledger.snapshotForStorage())
            Stores.declarations.save(declarations.snapshotForStorage())
            refreshBridgeStatus()
        }
    }

    /** 終了時。記録を落とさない。 */
    fun flush() {
        runCatching { Stores.usage.save(ledger.snapshotForStorage()) }
        runCatching { Stores.declarations.save(declarations.snapshotForStorage()) }
        runCatching { bridge.stop() }
        WindowControl.resumeAll()
    }

    // ------------------------------------------------------------------

    @Synchronized
    private fun evaluate(fg: ForegroundApp?) {
        if (fg == null || fg.processName in ProtectedProcesses) {
            if (_presentation.value != null) {
                _presentation.value = null
                releaseHold()
            }
            return
        }

        val now = System.currentTimeMillis()
        val nowSec = now / 1000

        // 期限切れの罰を落とす。時間が過ぎれば誰の手も借りずに解ける
        val live = Lockouts.prune(_lockouts.value, nowSec)
        if (live.size != _lockouts.value.size) {
            // 走り切った集中はここで拾う。終わった瞬間を捉える場所が他に無い
            awardFinishedFocus(_lockouts.value.filterNot { old -> live.any { it.uid == old.uid } })
            _lockouts.value = live
            Stores.lockouts.save(live)
        }

        // 終わった予約も落とす。放っておいても誤爆はしないが、溜まるので掃除する
        val liveReservations = Reservations.prune(_reservations.value, nowSec)
        if (liveReservations.size != _reservations.value.size) {
            _reservations.value = liveReservations
            Stores.reservations.save(liveReservations)
        }

        // 待つだけで通る関門(クールダウン・時間帯)があるので、押した瞬間だけでなく
        // 時間の経過でも見に来る。でないと通っているのに適用されないまま止まる
        applyReadyChanges()

        val file = _ruleFile.value

        // 拡張から来た URL。ブラウザが前面のときだけ、しかも報せが新しいときだけ使う。
        // 黙った拡張の古い URL で塞ぎ続けると、拡張が落ちただけで閉じ込められる
        val url = browserUrl?.takeIf {
            fg.processName in Browsers && now - browserUrlAtMs < URL_STALE_MS
        }

        // 罰で閉まっているかは、押し切りの猶予より先に見る。
        // 押し切りの罰が猶予に隠れてしまうと、罰が一度も効かない
        val locked = Lockouts.activeFor(
            all = live,
            packageName = fg.processName,
            tagsOfApp = file.tags[fg.processName] ?: emptySet(),
            nowSec = nowSec,
            url = url,
        )
        if (locked != null) {
            showLocked(fg, locked)
            return
        }

        // 閉め出しで閉じた区間を開き直す。同じアプリなら伸びるだけ。
        // ここが無いと、明けたあとの使用がどこにも残らず、次の閉め出しが来ない
        ledger.onForegroundChanged(fg.processName, nowSec)

        // 解禁券を使っているあいだはルールが全部止まる。ただし罰は上で先に見ている
        // ── ポイントで買えるのはルールの免除であって、科された罰の時間ではない
        if (nowSec < _settings.value.passUntilSec) {
            if (_presentation.value != null) {
                _presentation.value = null
                releaseHold()
            }
            return
        }

        if (now < (overrideUntil[fg.processName] ?: 0L)) return
        if (now < (dismissedUntil[fg.processName] ?: 0L)) return

        val context = EvalContext(
            now = LocalDateTime.now(),
            packageName = fg.processName,
            url = url,
            usage = ledger.snapshotFor(fg.processName),
            declaredRemainingMinutes = declarations.remainingMinutes(fg.processName),
            previousPackage = ledger.previousProcess(),
            sessionSeed = ledger.currentSessionSeed(),
            minutesSinceBreakOf = { ruleId, breakMinutes ->
                val rule = file.rules.firstOrNull { it.id == ruleId }
                if (rule == null) {
                    0
                } else {
                    ledger.minutesSinceBreak(breakMinutes) { name ->
                        rule.target.matches(name, file.tags[name] ?: emptySet())
                    }
                }
            },
            minutesSinceLastUseOf = { ruleId ->
                val rule = file.rules.firstOrNull { it.id == ruleId }
                if (rule == null) {
                    null
                } else {
                    ledger.minutesSinceLastUse { name ->
                        rule.target.matches(name, file.tags[name] ?: emptySet())
                    }
                }
            },
            withinReservation = Reservations.covers(
                _reservations.value,
                fg.processName,
                file.tags[fg.processName] ?: emptySet(),
                nowSec,
                url,
            ),
            // Windows にはアプリ内の画面を見分ける手立てが無い。ブラウザのショートは
            // sites(URL)側で当たるので、ここは空でよい
            screenSignals = emptySet(),
            overrideCountOf = { ruleId -> overrideCounts[ruleId] ?: 0 },
        )

        when (val decision = engine.decide(file.rules, context) { file.tags[it] ?: emptySet() }) {
            is Decision.Allow -> {
                if (_presentation.value != null) {
                    _presentation.value = null
                    releaseHold()
                }
            }

            is Decision.Act -> present(fg, decision)
            is Decision.Locked -> Unit // 上で処理済み
        }

        checkIgnoredWarnings(fg)
    }

    /**
     * 警告を出したあと、まだ同じアプリに居座っていたら「破った」とみなす。
     *
     * 予約は前面が変わったところで消す。アプリを離れたのに、その後で
     * 罰だけ降ってくるのは筋が通らない。
     */
    private fun checkIgnoredWarnings(fg: ForegroundApp) {
        if (ignoreDeadline.isEmpty()) return
        val now = System.currentTimeMillis()
        val file = _ruleFile.value

        val due = ignoreDeadline.filterValues { it in 1..now }.keys.toList()
        for (ruleId in due) {
            ignoreDeadline.remove(ruleId)
            val rule = file.rules.firstOrNull { it.id == ruleId } ?: continue
            if (!rule.target.matches(fg.processName, file.tags[fg.processName] ?: emptySet())) continue
            if (!punishedWarnIgnores.add("$ruleId|${fg.processName}")) continue
            punish(fg.processName, rule, PointReason.WARN_IGNORED)
        }
    }

    private fun showLocked(fg: ForegroundApp, lockout: Lockout) {
        // 閉め出しているあいだは使用時間を数えない。ここで止めないと、
        // 閉まっている時間まで「使った時間」に化けて、明けた瞬間にまた閉まる
        ledger.onForegroundChanged(null)

        val key = "${fg.processName}|locked|${lockout.untilEpochSec}"
        if (_presentation.value?.key == key) {
            heldApp = fg
            return
        }
        heldApp = fg
        heldRule = null
        _presentation.value = Presentation.Locked(
            key = key,
            processName = fg.processName,
            label = fg.label,
            reason = lockout.reason,
            untilEpochSec = lockout.untilEpochSec,
            isFocus = lockout.isChosen,
            abortCost = if (lockout.canCancelFreelyAt(nowSec())) 0 else (lockout.earlyExit?.points ?: 0),
            balance = _balance.value,
        )
    }

    /**
     * 「閉じる」の前に、薄い予告をそっと出す。Warn の見せ方を借りている。
     *
     * 一続きにつき1回だけ。数えている最中に判定が来ても本番を先に出さない。
     * 数え終わる前にアプリを離れたら取りやめる。
     */
    private fun withPrewarn(fg: ForegroundApp, act: Decision.Act, proceed: () -> Unit) {
        val seconds = com.dopachiru.core.action.ActionExtras.prewarnSeconds(act.params)
        val key = "${fg.processName}|${act.rule.id}|${ledger.currentSessionSeed()}"
        if (seconds <= 0 || key in prewarnDone) {
            proceed()
            return
        }
        if (key in prewarnPending) return
        prewarnPending.add(key)
        val pkey = "${fg.processName}|prewarn|${act.rule.id}"
        _presentation.value = Presentation.Warn(pkey, "${fg.label} を閉じます(あと${seconds}秒)")
        scope.launch {
            delay(seconds * 1000L)
            prewarnPending.remove(key)
            prewarnDone.add(key)
            if (_foreground.value?.processName == fg.processName) {
                proceed()
            } else if (_presentation.value?.key == pkey) {
                _presentation.value = null
            }
        }
    }

    private fun present(fg: ForegroundApp, act: Decision.Act) {
        when (act.action.id) {
            BlockAction.id -> withPrewarn(fg, act) {
                showBlock(
                    fg = fg,
                    rule = act.rule,
                    reflection = act.params.string(BlockAction.KEY_REFLECTION),
                    minSeconds = act.params.int(BlockAction.KEY_MIN_SECONDS, 15),
                    allowOverride = act.params.bool(BlockAction.KEY_ALLOW_OVERRIDE, true),
                    params = act.params,
                )
            }

            LockoutAction.id -> withPrewarn(fg, act) { lockOut(fg, act) }

            WarnAction.id -> {
                val repeatMs = act.params.int(WarnAction.KEY_REPEAT_MINUTES, 5) * 60_000L
                val now = System.currentTimeMillis()
                if (now - (warnShownAt[act.rule.id] ?: 0L) < repeatMs) return
                warnShownAt[act.rule.id] = now

                val ignoreMinutes = act.params.int(WarnAction.KEY_IGNORE_MINUTES, 5)
                if (ignoreMinutes > 0) {
                    ignoreDeadline[act.rule.id] = now + ignoreMinutes * 60_000L
                }

                val seconds = act.params.int(WarnAction.KEY_SECONDS, 5)
                val message = act.params.string(WarnAction.KEY_MESSAGE)
                    .ifBlank { "そろそろやめる時間。" }
                val key = "${fg.processName}|warn|${act.rule.id}"
                _presentation.value = Presentation.Warn(key, message)
                scope.launch {
                    delay(seconds * 1000L)
                    if (_presentation.value?.key == key) _presentation.value = null
                }
            }

            DelayAction.id -> {
                val key = "${fg.processName}|delay|${act.rule.id}|${ledger.currentSessionSeed()}"
                if (_presentation.value?.key == key || key in passedDelays) return

                val text = act.params.string(DelayAction.KEY_MESSAGE)
                val ctx = EvalContext(
                    now = LocalDateTime.now(),
                    packageName = fg.processName,
                    usage = ledger.snapshotFor(fg.processName),
                    sessionSeed = ledger.currentSessionSeed(),
                    currentRuleId = act.rule.id,
                )
                heldApp = fg
                heldRule = act.rule
                _presentation.value = Presentation.Delay(
                    key = key,
                    label = fg.label,
                    message = Rotation.pick(text, ctx, "何をしに開いた?"),
                    seconds = act.params.int(DelayAction.KEY_SECONDS, 5),
                    rotationNote = if (Rotation.rotates(text)) Rotation.EXPLANATION else "",
                )
            }

            RadioAction.id -> {
                // 覗いているあいだは覆い直さない
                if (System.currentTimeMillis() < (radioPeekUntil[fg.processName] ?: 0L)) {
                    if (_presentation.value is Presentation.Radio) {
                        _presentation.value = null
                        releaseHold()
                    }
                    return
                }
                val key = "${fg.processName}|radio|${act.rule.id}"
                if (_presentation.value?.key == key) return
                // 覆うだけ。最小化も一時停止もしないので heldApp は取らない(音は流れ続ける)
                _presentation.value = Presentation.Radio(
                    key = key,
                    label = fg.label,
                    message = Rotation.pick(
                        act.params.string(RadioAction.KEY_MESSAGE),
                        EvalContext(
                            now = LocalDateTime.now(),
                            packageName = fg.processName,
                            usage = ledger.snapshotFor(fg.processName),
                            sessionSeed = ledger.currentSessionSeed(),
                            currentRuleId = act.rule.id,
                        ),
                        "耳で聞く。目は要らない。",
                    ),
                    peekEffort = act.params.string(RadioAction.KEY_PEEK_EFFORT, BlockAction.Effort.HOLD),
                    peekSeconds = act.params.int(RadioAction.KEY_PEEK_SECONDS, 10),
                )
            }

            IntentionAction.id -> {
                val seed = ledger.currentSessionSeed()
                val sessionKey = "${fg.processName}|$seed"
                val existing = intentions[sessionKey]
                if (existing == null) {
                    val key = "${fg.processName}|intention-input|$seed"
                    if (_presentation.value?.key == key) return
                    _presentation.value = Presentation.IntentionInput(
                        key = key,
                        processName = fg.processName,
                        label = fg.label,
                        prompt = act.params.string(IntentionAction.KEY_PROMPT).ifBlank { "何をしに開いた?" },
                        suggestions = act.params.string(IntentionAction.KEY_SUGGESTIONS)
                            .split("\n").map { it.trim() }.filter { it.isNotBlank() },
                    )
                    // 入力は押さえる(書くまで下を触らせない)
                    heldApp = fg
                } else {
                    val minutes = if (act.params.bool(IntentionAction.KEY_SHOW_TIMER, true)) {
                        ledger.snapshotFor(fg.processName).currentSessionMinutes
                    } else {
                        null
                    }
                    val key = "${fg.processName}|intention|$seed|$minutes"
                    if (_presentation.value?.key == key) return
                    // 札は押さえない。操作は下に届く
                    if (heldApp != null) releaseHold()
                    heldApp = null
                    _presentation.value = Presentation.Intention(key, existing, minutes)
                }
            }

            TimerAction.id -> {
                val snapshot = ledger.snapshotFor(fg.processName)
                val minutes = snapshot.currentSessionMinutes
                if (minutes < act.params.int(TimerAction.KEY_AFTER_MINUTES, 0)) return

                val today = if (act.params.bool(TimerAction.KEY_SHOW_TODAY, true)) {
                    snapshot.usageMinutesIn(ResetPolicy())
                } else {
                    null
                }
                val key = "${fg.processName}|timer|${act.rule.id}|$minutes|$today"
                if (_presentation.value?.key == key) return
                // 押さえない。何も止めないので heldApp は取らない
                _presentation.value = Presentation.Timer(key, minutes, today)
            }

            DeclareAction.id -> {
                val remaining = declarations.remainingMinutes(fg.processName)
                when {
                    remaining == null -> {
                        // 宣言が切れた = 次の回。超過の印を落として数え直す
                        punishedOverruns.remove("${fg.processName}|${act.rule.id}")
                        val key = "${fg.processName}|declare|${act.rule.id}"
                        if (_presentation.value?.key == key) return
                        _presentation.value = Presentation.Declare(
                            key = key,
                            processName = fg.processName,
                            label = fg.label,
                            maxMinutes = act.params.int(DeclareAction.KEY_MAX_MINUTES, 30),
                            defaultMinutes = act.params.int(DeclareAction.KEY_DEFAULT_MINUTES, 10),
                            requireReason = act.params.bool(DeclareAction.KEY_REQUIRE_REASON, false),
                        )
                        heldApp = fg
                    }

                    // 押し切りを待たずにここで罰する。超えた時点がすでに違反なので
                    remaining <= 0 -> {
                        if (punishedOverruns.add("${fg.processName}|${act.rule.id}")) {
                            punish(fg.processName, act.rule, PointReason.DECLARE_OVERRUN)
                        }
                        showBlock(
                            fg = fg,
                            rule = act.rule,
                            reflection = act.params.string(DeclareAction.KEY_REFLECTION)
                                .ifBlank { "宣言した時間は終わり。" },
                            minSeconds = 15,
                            allowOverride = true,
                            params = act.params,
                        )
                    }

                    else -> if (_presentation.value != null) {
                        _presentation.value = null
                        releaseHold()
                    }
                }
            }
        }
    }

    private fun showBlock(
        fg: ForegroundApp,
        rule: Rule,
        reflection: String,
        minSeconds: Int,
        allowOverride: Boolean,
        params: com.dopachiru.core.param.Params,
    ) {
        val policy = _settings.value.pointPolicy
        val cost = policy.overrideCost(rule.consequence.breakPoints)
        val balance = _balance.value

        // 値段と残高もキーに含める。残高が変われば表示を合わせ直す必要がある
        val key = "${fg.processName}|block|${rule.id}|${if (allowOverride) "o" else "x"}|$cost|$balance"
        if (_presentation.value?.key == key) {
            heldApp = fg
            heldRule = rule
            return
        }
        if (isThrashing()) {
            // 出しては消えるを繰り返している = こちらの不具合。
            // 巻き込まれ続けるより止まったほうがましなので、自分から降りる
            emergencyPause()
            return
        }
        heldApp = fg
        heldRule = rule

        val ctx = EvalContext(
            now = LocalDateTime.now(),
            packageName = fg.processName,
            usage = ledger.snapshotFor(fg.processName),
            sessionSeed = ledger.currentSessionSeed(),
            currentRuleId = rule.id,
        )

        _presentation.value = Presentation.Block(
            key = key,
            processName = fg.processName,
            label = fg.label,
            ruleName = rule.name,
            reflection = Rotation.pick(reflection, ctx, "いま開く必要はある?"),
            rotationNote = if (Rotation.rotates(reflection)) Rotation.EXPLANATION else "",
            releaseEffort = params.string(
                BlockAction.KEY_RELEASE_EFFORT,
                BlockAction.Effort.TAP,
            ),
            minSeconds = minSeconds,
            allowOverride = allowOverride,
            overrideCost = cost,
            balance = balance,
            penaltyNote = if (rule.consequence.locksNothing) {
                ""
            } else {
                "押し切ると${rule.consequence.lockScope.label}が${rule.consequence.lockMinutes}分閉まります"
            },
        )
    }

    /**
     * ブロック中のあいだ、選んだ強さを効かせ続ける。
     *
     * 最小化は毎回かけ直す。1回だけだと Alt+Tab で戻れてしまう。
     * 一時停止は1回でよいが、解除のときに必ず戻す責任がある。
     */
    private fun enforce() {
        val held = heldApp ?: return
        val showing = _presentation.value
        // 罰で閉まっているときも同じ強さで押さえる。
        // オーバーレイだけだと Alt+Tab で裏から触れてしまい、罰にならない
        if (showing !is Presentation.Block && showing !is Presentation.Locked) return

        when (_settings.value.blockStrength) {
            BlockStrength.OVERLAY -> Unit
            BlockStrength.MINIMIZE -> WindowControl.minimize(held.hwnd)
            BlockStrength.SUSPEND -> if (!WindowControl.isSuspended(held.pid)) {
                WindowControl.suspend(held.pid)
            }
        }
    }

    /**
     * 短い間にブロック画面を出し直しすぎていないか。
     *
     * 正しく動いていれば、1つのブロックは出たまま留まる。何度も出し直しているなら
     * 「出す → 閉じる → すぐまた出す」の輪に入っている。
     */
    private fun isThrashing(): Boolean {
        val now = System.currentTimeMillis()
        blockShownTimes.addLast(now)
        while (blockShownTimes.isNotEmpty() && now - blockShownTimes.first() > THRASH_WINDOW_MS) {
            blockShownTimes.removeFirst()
        }
        return blockShownTimes.size >= THRASH_LIMIT
    }

    /** 前面を見に行く間隔。API 呼び出し数回ぶんなので、負荷は誤差。 */
    private const val POLL_MS = 1_000L

    /** ポイント履歴の保持件数。増減の理由を辿れれば足りるので、際限なくは持たない。 */
    private const val POINT_HISTORY_LIMIT = 500

    /** 段階を数える窓。これより古い封鎖は「別の機会」として数え直す。 */
    private const val ESCALATION_WINDOW_SEC = 24L * 60 * 60

    /** 「やめる」を押したあと、そのアプリを見逃す時間。離れる隙を作るため。 */
    private const val DISMISS_GRACE_MS = 6_000L

    private const val THRASH_WINDOW_MS = 60_000L
    private const val THRASH_LIMIT = 8

    /** 記録をディスクに落とす間隔。 */
    private const val PERSIST_MS = 60_000L

    /**
     * 拡張からの報せをいつまで信じるか。
     *
     * 拡張は 30 秒ごとに近況を送ってくる(MV3 の目覚ましはこれが下限)。
     * 遅れることがあるので、その 5 倍待ってから見限る。
     *
     * 短すぎると、同じページに座り続けているだけで規則が外れる ──
     * 「15分見たら止める」のような後から効く規則が、いちばん要る場面で効かなくなる。
     * 長すぎると、拡張が落ちたあともブラウザが塞がったままになる。
     *
     * なおブラウザが前面に無いあいだは、そもそも URL を見ないので影響しない。
     */
    private const val URL_STALE_MS = 150_000L
}
