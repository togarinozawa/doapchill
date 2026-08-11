package com.dopachiru.desktop

import com.dopachiru.core.DopaCore
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.model.Rule
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

    /** 警告を最後に出した時刻。ルールIDごと。 */
    private val warnShownAt = HashMap<Long, Long>()

    private val ownPid: Long = ProcessHandle.current().pid()

    fun start() {
        DopaCore.registerAll()
        _settings.value = Stores.settings.load()
        _ruleFile.value = Stores.rules.load()
        ledger.restore(Stores.usage.load())
        declarations.restore(Stores.declarations.load())

        scope.launch { watchLoop() }
        scope.launch { persistLoop() }
    }

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
            rules = file.rules + rule.copy(id = file.nextId),
            nextId = file.nextId + 1,
        )
    }

    fun removeRule(id: Long) = updateRules { it.copy(rules = it.rules.filter { r -> r.id != id }) }

    fun setRuleEnabled(id: Long, enabled: Boolean) = updateRules { file ->
        file.copy(rules = file.rules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun todayBreakdown(): List<Pair<String, Int>> = ledger.breakdownIn(ResetPolicy())

    fun todayTotalMinutes(): Int = ledger.totalMinutesIn(ResetPolicy())

    // ------------------------------------------------------------------

    /** ブロック画面の「わかった、やめる」。押さえていたアプリを引っ込める。 */
    fun dismissBlock() {
        val held = heldApp
        _presentation.value = null
        releaseHold()
        if (held != null) WindowControl.minimize(held.hwnd)
    }

    /** ブロック画面の「それでも使う」。 */
    fun overrideBlock() {
        val held = heldApp ?: return
        overrideUntil[held.processName] =
            System.currentTimeMillis() + _settings.value.overrideGraceMinutes * 60_000L
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

        if (System.currentTimeMillis() < (overrideUntil[fg.processName] ?: 0L)) return

        val file = _ruleFile.value
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
        }
    }

    private fun present(fg: ForegroundApp, act: Decision.Act) {
        when (act.action.id) {
            BlockAction.id -> showBlock(
                fg = fg,
                ruleId = act.rule.id,
                ruleName = act.rule.name,
                reflection = act.params.string(BlockAction.KEY_REFLECTION),
                minSeconds = act.params.int(BlockAction.KEY_MIN_SECONDS, 15),
                allowOverride = act.params.bool(BlockAction.KEY_ALLOW_OVERRIDE, true),
            )

            WarnAction.id -> {
                val repeatMs = act.params.int(WarnAction.KEY_REPEAT_MINUTES, 5) * 60_000L
                val now = System.currentTimeMillis()
                if (now - (warnShownAt[act.rule.id] ?: 0L) < repeatMs) return
                warnShownAt[act.rule.id] = now

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

                    remaining <= 0 -> showBlock(
                        fg = fg,
                        ruleId = act.rule.id,
                        ruleName = act.rule.name,
                        reflection = act.params.string(DeclareAction.KEY_REFLECTION)
                            .ifBlank { "宣言した時間は終わり。" },
                        minSeconds = 15,
                        allowOverride = true,
                    )

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
        ruleId: Long,
        ruleName: String,
        reflection: String,
        minSeconds: Int,
        allowOverride: Boolean,
    ) {
        val key = "${fg.processName}|block|$ruleId|${if (allowOverride) "o" else "x"}"
        if (_presentation.value?.key == key) {
            heldApp = fg
            return
        }
        heldApp = fg
        _presentation.value = Presentation.Block(
            key = key,
            processName = fg.processName,
            label = fg.label,
            ruleName = ruleName,
            reflection = reflection,
            minSeconds = minSeconds,
            allowOverride = allowOverride,
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
        if (_presentation.value !is Presentation.Block) return

        when (_settings.value.blockStrength) {
            BlockStrength.OVERLAY -> Unit
            BlockStrength.MINIMIZE -> WindowControl.minimize(held.hwnd)
            BlockStrength.SUSPEND -> if (!WindowControl.isSuspended(held.pid)) {
                WindowControl.suspend(held.pid)
            }
        }
    }

    /** 前面を見に行く間隔。API 呼び出し数回ぶんなので、負荷は誤差。 */
    private const val POLL_MS = 1_000L

    /** 記録をディスクに落とす間隔。 */
    private const val PERSIST_MS = 60_000L
}
