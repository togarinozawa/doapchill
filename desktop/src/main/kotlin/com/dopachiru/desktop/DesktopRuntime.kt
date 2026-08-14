package com.dopachiru.desktop

import com.dopachiru.core.DopaCore
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Lockouts
import com.dopachiru.core.model.Rule
import com.dopachiru.core.points.PointEvent
import com.dopachiru.core.points.PointReason
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.desktop.data.DeclarationTracker
import com.dopachiru.desktop.data.DesktopSettings
import com.dopachiru.desktop.data.RuleFile
import com.dopachiru.desktop.data.Stores
import com.dopachiru.desktop.data.UsageLedger
import com.dopachiru.desktop.platform.BlockStrength
import com.dopachiru.desktop.platform.ForegroundApp
import com.dopachiru.desktop.platform.ForegroundWatcher
import com.dopachiru.desktop.platform.ProtectedProcesses
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

    /**
     * 宣言超過の罰を科し終えた組み合わせ。
     *
     * 超過中は毎秒ここを通るので、印を付けないと罰が積み上がる。
     */
    private val punishedOverruns = HashSet<String>()

    private val _lockouts = MutableStateFlow<List<Lockout>>(emptyList())
    val lockouts: StateFlow<List<Lockout>> = _lockouts.asStateFlow()

    private val _points = MutableStateFlow<List<PointEvent>>(emptyList())
    val points: StateFlow<List<PointEvent>> = _points.asStateFlow()

    private val _balance = MutableStateFlow(0)
    val balance: StateFlow<Int> = _balance.asStateFlow()

    private val ownPid: Long = ProcessHandle.current().pid()

    fun start() {
        DopaCore.registerAll()
        _settings.value = Stores.settings.load()
        _ruleFile.value = Stores.rules.load()
        ledger.restore(Stores.usage.load())
        declarations.restore(Stores.declarations.load())
        // 罰と残高も戻す。再起動で罰が消えるなら罰にならない
        _lockouts.value = Lockouts.prune(Stores.lockouts.load(), nowSec())
        _points.value = Stores.points.load()
        _balance.value = _points.value.sumOf { it.delta }

        scope.launch { watchLoop() }
        scope.launch { persistLoop() }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    // ------------------------------------------------------------------

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

    fun addRule(rule: Rule) = updateRules { file ->
        file.copy(
            // uid は端末をまたいで一意。id と違って、作った端末が変わっても付いて回る
            rules = file.rules + rule.copy(
                id = file.nextId,
                uid = rule.uid.ifBlank { java.util.UUID.randomUUID().toString() },
            ),
            nextId = file.nextId + 1,
        )
    }

    fun removeRule(id: Long) = updateRules { it.copy(rules = it.rules.filter { r -> r.id != id }) }

    /** ルールを1つ差し替える。条件・罰の編集から使う。 */
    fun updateRule(rule: Rule) = updateRules { file ->
        file.copy(rules = file.rules.map { if (it.id == rule.id) rule else it })
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) = updateRules { file ->
        file.copy(rules = file.rules.map { if (it.id == id) it.copy(enabled = enabled) else it })
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
        heldRule?.let { punish(held.processName, it, PointReason.OVERRIDE) }
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
            val minutes = consequence.lockMinutes.coerceAtMost(Consequence.MAX_LOCK_MINUTES)
            val now = nowSec()
            _lockouts.value = _lockouts.value + Lockout(
                target = target,
                untilEpochSec = now + minutes * 60L,
                reason = rule.name,
                createdAtEpochSec = now,
            )
            Stores.lockouts.save(_lockouts.value)
        }

        if (policy.enabled) {
            addPoints(policy.breakDelta(consequence.breakPoints), reason, rule.name)
        }
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
    fun passUntil(): Long = _settings.value.passUntilSec.takeIf { nowSec() < it } ?: 0L

    /** 開発・確認用。罰を手で解く経路はここだけ。 */
    fun clearLockouts() {
        _lockouts.value = emptyList()
        Stores.lockouts.save(emptyList())
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
        }
    }

    /** 終了時。記録を落とさない。 */
    fun flush() {
        runCatching { Stores.usage.save(ledger.snapshotForStorage()) }
        runCatching { Stores.declarations.save(declarations.snapshotForStorage()) }
        WindowControl.resumeAll()
    }

    // ------------------------------------------------------------------

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
            _lockouts.value = live
            Stores.lockouts.save(live)
        }

        val file = _ruleFile.value

        // 罰で閉まっているかは、押し切りの猶予より先に見る。
        // 押し切りの罰が猶予に隠れてしまうと、罰が一度も効かない
        val locked = Lockouts.activeFor(
            all = live,
            packageName = fg.processName,
            tagsOfApp = file.tags[fg.processName] ?: emptySet(),
            nowSec = nowSec,
        )
        if (locked != null) {
            showLocked(fg, locked)
            return
        }

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
            usage = ledger.snapshotFor(fg.processName),
            declaredRemainingMinutes = declarations.remainingMinutes(fg.processName),
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
        )
    }

    private fun present(fg: ForegroundApp, act: Decision.Act) {
        when (act.action.id) {
            BlockAction.id -> showBlock(
                fg = fg,
                rule = act.rule,
                reflection = act.params.string(BlockAction.KEY_REFLECTION),
                minSeconds = act.params.int(BlockAction.KEY_MIN_SECONDS, 15),
                allowOverride = act.params.bool(BlockAction.KEY_ALLOW_OVERRIDE, true),
            )

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
        _presentation.value = Presentation.Block(
            key = key,
            processName = fg.processName,
            label = fg.label,
            ruleName = rule.name,
            reflection = reflection,
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

    /** 「やめる」を押したあと、そのアプリを見逃す時間。離れる隙を作るため。 */
    private const val DISMISS_GRACE_MS = 6_000L

    private const val THRASH_WINDOW_MS = 60_000L
    private const val THRASH_LIMIT = 8

    /** 記録をディスクに落とす間隔。 */
    private const val PERSIST_MS = 60_000L
}
