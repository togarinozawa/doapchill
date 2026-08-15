package com.dopachiru.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.dopachiru.R
import com.dopachiru.block.BlockScreen
import com.dopachiru.block.DeclareScreen
import com.dopachiru.block.DelayScreen
import com.dopachiru.block.LockoutScreen
import com.dopachiru.block.OverlayHost
import com.dopachiru.block.OverlayMode
import com.dopachiru.block.SelfDefenseScreen
import com.dopachiru.block.SessionTimerScreen
import com.dopachiru.block.UnlockPromptScreen
import com.dopachiru.block.WarnScreen
import com.dopachiru.core.action.Rotation
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.TimerAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Rule
import com.dopachiru.core.points.PointReason
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * フォアグラウンドのアプリを検知して、ルールに従いオーバーレイを出す本体。
 *
 * ブロック画面は `TYPE_ACCESSIBILITY_OVERLAY` で出す(OverlayHost を参照)。
 * このサービスの Context からしか出せない代わりに、
 * SYSTEM_ALERT_WINDOW が不要で、ナビゲーションバーの上にも被せられる。
 */
class DopaAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayHost
    private val handler = Handler(Looper.getMainLooper())

    private var launcherPackages: Set<String> = emptySet()
    private var imePackages: Set<String> = emptySet()
    private var settingsPackages: Set<String> = emptySet()
    private val labelCache = HashMap<String, String>()

    private var foregroundPackage: String? = null
    private var activeLogId: Long? = null

    /** 引き止めを一度見送ったあと、しばらく出し直さないための猶予。 */
    private var selfDefenseSnoozedUntil = 0L

    /** 押し切られたアプリを、しばらく再ブロックしないための猶予。 */
    private val overrideUntil = HashMap<String, Long>()

    /** 警告を最後に出した時刻。ルールIDごと。 */
    private val warnShownAt = HashMap<Long, Long>()

    /** 「無視した」の判定を予約したもの。取り消せるように持っておく。 */
    private val pendingIgnoreChecks = HashMap<Long, Runnable>()

    /**
     * 宣言超過の罰を科し終えた組み合わせ。
     *
     * 超過中は判定のたびにここを通るので、印を付けないと数十秒おきに罰が積み上がる。
     * 宣言し直したら消す ── 次の回はまた1から数える。
     */
    private val punishedOverruns = HashSet<String>()

    /**
     * 警告を無視した罰を科し終えた組み合わせ。そのアプリを離れたら消える。
     *
     * 警告は成立しているあいだ繰り返し出るので、出るたびに罰していると
     * 1時間ほどで残高が下限に張り付き、そこから何をしても押し切れなくなる。
     * 「無視して使い続けた」は**その一続きにつき1回**と数える。
     */
    private val punishedWarnIgnores = HashSet<String>()

    /** 待ち時間を通したセッション。同じ使用のあいだ出し直さないため。 */
    private val passedDelays = HashSet<String>()

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> onUserPresent()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    // 省電力モードを条件に使っているルールがあるかもしれないので見直す
                    foregroundPackage?.let { evaluate(it) }
                }
            }
        }
    }

    /**
     * 判定を見に来る処理。
     *
     * 一定間隔で回すのではなく、毎回「次はいつ見に来ればよいか」を条件に聞いて
     * そのぶんだけ眠る。時間帯や連続使用時間は次に変わる時刻が計算できるので、
     * たとえば「連続15分で警告」なら、開いた直後は15分後まで一度も起きない。
     */
    private val evaluateOnce = Runnable {
        val pkg = foregroundPackage
        if (pkg != null) evaluate(pkg)
        scheduleNextEvaluation()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
        DopaRuntime.init(this)
        overlay = OverlayHost(this)
        // 封印中は戻るキーを押しても何も起きない
        overlay.onBackPressed = { }

        launcherPackages = resolveLauncherPackages()
        imePackages = resolveImePackages()
        settingsPackages = resolveSettingsPackages()

        registerReceiver(
            systemReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
        )
        runCatching { MonitorService.start(this) }
    }

    /**
     * 次の判定を予約する。
     *
     * 前面のアプリを狙っているルールが1つも無いか、画面が消えていれば、
     * 何も予約しない = 完全に止まる。
     */
    private fun scheduleNextEvaluation() {
        handler.removeCallbacks(evaluateOnce)
        val pkg = foregroundPackage ?: return
        if (!DopaRuntime.screenOn) return
        if (pkg in launcherPackages || pkg in settingsPackages) return
        if (!DopaRuntime.isTargeted(pkg)) return

        val ceiling = if (DopaRuntime.batterySaverMode) CHECK_CEILING_SAVER_MS else CHECK_CEILING_MS
        val delay = DopaRuntime.nextCheckDelayMs(pkg, CHECK_FLOOR_MS, ceiling)
        handler.postDelayed(evaluateOnce, delay)
    }

    private fun onScreenOff() {
        handler.removeCallbacks(evaluateOnce)
        overlay.hide()
        DopaRuntime.onScreenOff()
    }

    private fun onScreenOn() {
        DopaRuntime.onScreenOn()
        // 前面のアプリは続けて来る検知イベントで入り直るが、
        // 来なかったときのために今見えているものから拾い直しておく
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (pkg != null && pkg != packageName) handleForeground(pkg)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in IGNORED_PACKAGES || pkg in imePackages) return
        handleForeground(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        live = null
        handler.removeCallbacks(evaluateOnce)
        pendingIgnoreChecks.values.forEach { handler.removeCallbacks(it) }
        pendingIgnoreChecks.clear()
        runCatching { unregisterReceiver(systemReceiver) }
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }

    /**
     * 外から「いま見直してくれ」と言われたときの入口。
     *
     * 予定の開始・終了のように、こちらからは予測できない出来事のために置いてある。
     * 待ち時間の予約も取り直すので、次に起きる時刻も新しい状況に合う。
     */
    private fun requestImmediateEvaluation() {
        handler.post {
            foregroundPackage?.let { evaluate(it) }
            scheduleNextEvaluation()
        }
    }

    // ------------------------------------------------------------------

    private fun handleForeground(pkg: String) {
        if (pkg == foregroundPackage) {
            evaluate(pkg)
            scheduleNextEvaluation()
            return
        }
        // アプリを離れた = 一続きの終わり。無視の印を落として数え直す
        punishedWarnIgnores.removeAll { it.endsWith("|$foregroundPackage") }
        foregroundPackage = pkg
        DopaRuntime.onForegroundChanged(pkg)

        if (pkg in settingsPackages) {
            handler.removeCallbacks(evaluateOnce)
            maybeDefendSelf()
            return
        }
        if (pkg in launcherPackages) {
            handler.removeCallbacks(evaluateOnce)
            overlay.hide()
            maybeGreetOnHome()
            return
        }
        evaluate(pkg)
        scheduleNextEvaluation()
    }

    /**
     * 設定アプリでドパチル自身のページが開かれたら引き止める。
     *
     * 無効化そのものは必ずできるようにしてある。自分で入れたアプリを
     * 自分で止められなくなるのは抑止ではなく事故なので、
     * ここでやるのは「一拍置かせる」ことだけ。
     */
    private fun maybeDefendSelf() {
        if (System.currentTimeMillis() < selfDefenseSnoozedUntil) return

        DopaRuntime.scope.launch {
            if (!DopaRuntime.settings.selfDefense.first()) return@launch
            val streak = DopaRuntime.stats.streak.first()
            handler.post defend@{
                if (foregroundPackage !in settingsPackages) return@defend
                if (!isOwnSettingsPageVisible()) return@defend
                val key = "self|defense"
                if (overlay.currentKey == key) return@defend
                overlay.show(key, OverlayMode.BLOCKING) {
                    SelfDefenseScreen(
                        streak = streak,
                        minSeconds = SELF_DEFENSE_SECONDS,
                        onProceed = {
                            selfDefenseSnoozedUntil = System.currentTimeMillis() + SELF_DEFENSE_SNOOZE_MS
                            overlay.hide()
                        },
                        onGoBack = {
                            overlay.hide()
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        },
                    )
                }
            }
        }
    }

    /** 設定アプリの画面に、このアプリの名前が出ているか。 */
    private fun isOwnSettingsPageVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        val label = getString(R.string.app_name)
        return runCatching {
            root.findAccessibilityNodeInfosByText(label).isNotEmpty()
        }.getOrDefault(false)
    }

    private fun evaluate(pkg: String) {
        if (pkg in launcherPackages) return

        val decision = DopaRuntime.decide(pkg)

        // 罰で閉まっているかは、押し切りの猶予より先に見る。
        // 押し切りの罰が猶予に隠れてしまうと、罰が一度も効かない
        if (decision is Decision.Locked) {
            showLockout(pkg, decision.lockout)
            return
        }

        // 学習予定の最中は押し切りの猶予を効かせない。
        // 予定が始まる前に押し切っておいて、そのまま持ち込むのを防ぐ。
        if (!DopaRuntime.studyInSession() &&
            System.currentTimeMillis() < (overrideUntil[pkg] ?: 0L)
        ) return

        when (decision) {
            is Decision.Allow -> if (overlay.currentKey?.startsWith("$pkg|") == true) overlay.hide()
            is Decision.Act -> present(pkg, decision)
            is Decision.Locked -> Unit // 上で処理済み
        }
    }

    /**
     * 罰で閉まっているときの画面。
     *
     * 押し切りの猶予([overrideUntil])より先に効かせる ── 押し切った直後に
     * 罰が科されるので、猶予を尊重すると罰そのものが素通りしてしまう。
     */
    private fun showLockout(pkg: String, lockout: Lockout) {
        val key = "$pkg|locked|${lockout.untilEpochSec}"
        if (overlay.currentKey == key) return

        val label = appLabel(pkg)
        overlay.show(key, OverlayMode.BLOCKING, coverSystemBars = true) {
            LockoutScreen(
                appLabel = label,
                reason = lockout.reason,
                untilEpochSec = lockout.untilEpochSec,
                onGoHome = {
                    overlay.hide()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                },
            )
        }
    }

    private fun present(pkg: String, act: Decision.Act) {
        when (act.action.id) {
            BlockAction.id -> showBlock(
                pkg = pkg,
                rule = act.rule,
                reflection = act.params.string(BlockAction.KEY_REFLECTION),
                minSeconds = act.params.int(BlockAction.KEY_MIN_SECONDS, 15),
                coverSystemBars = act.params.bool(BlockAction.KEY_COVER_SYSTEM_BARS, true),
                allowOverride = act.params.bool(BlockAction.KEY_ALLOW_OVERRIDE, true),
                actionId = act.action.id,
                violation = PointReason.OVERRIDE,
            )

            WarnAction.id -> showWarn(pkg, act)
            DeclareAction.id -> showDeclareOrPass(pkg, act)
            DelayAction.id -> showDelay(pkg, act)
            TimerAction.id -> showTimer(pkg, act)
            else -> Unit
        }
    }

    /**
     * 数秒待たせて、必ず通す。
     *
     * 通したあとは同じセッションのあいだ出し直さない。待つたびに出ては
     * 遅延ではなくロックになってしまう。
     */
    private fun showDelay(pkg: String, act: Decision.Act) {
        val seconds = act.params.int(DelayAction.KEY_SECONDS, 5)
        val key = "$pkg|delay|${act.rule.id}|${DopaRuntime.sessionSeed()}"
        if (overlay.currentKey == key) return
        if (key in passedDelays) return

        val text = act.params.string(DelayAction.KEY_MESSAGE)
        val message = DopaRuntime.rotate(pkg, act.rule.id, text, "何をしに開いた?")
        val label = appLabel(pkg)

        DopaRuntime.scope.launch {
            DopaRuntime.stats.recordBlockShown(pkg, act.rule.id, act.rule.name, act.action.id)
        }

        overlay.show(key, OverlayMode.BLOCKING) {
            DelayScreen(
                appLabel = label,
                message = message,
                seconds = seconds,
                rotationNote = if (Rotation.rotates(text)) Rotation.EXPLANATION else "",
                onDone = {
                    passedDelays.add(key)
                    overlay.hide()
                },
            )
        }
    }

    /** 経過時間を隅に出し続ける。操作は一切止めない。 */
    private fun showTimer(pkg: String, act: Decision.Act) {
        val afterMinutes = act.params.int(TimerAction.KEY_AFTER_MINUTES, 0)
        val minutes = DopaRuntime.usage.snapshotFor(pkg, DopaRuntime.now()).currentSessionMinutes
        if (minutes < afterMinutes) return

        val today = if (act.params.bool(TimerAction.KEY_SHOW_TODAY, true)) {
            DopaRuntime.usage.snapshotFor(pkg, DopaRuntime.now()).usageMinutesIn(ResetPolicy())
        } else {
            null
        }

        // 分が変わるたびにキーが変わる = 表示が更新される
        val key = "$pkg|timer|${act.rule.id}|$minutes|$today"
        if (overlay.currentKey == key) return
        overlay.show(key, OverlayMode.PASS_THROUGH) { SessionTimerScreen(minutes, today) }
    }

    private fun showBlock(
        pkg: String,
        rule: Rule,
        reflection: String,
        minSeconds: Int,
        coverSystemBars: Boolean,
        allowOverride: Boolean,
        actionId: String,
        violation: PointReason,
    ) {
        val ruleId = rule.id
        val ruleName = rule.name
        // 学習予定の最中は、ルールの設定に関わらず押し切れない
        val canOverride = allowOverride && !DopaRuntime.studyInSession()

        // 押し切れないときの出口。予定そのものを中断する経路を残す。
        // これが無いと、許可リストを間違えたときに端末ごと詰む。
        val abortWindowId =
            if (canOverride) null else DopaRuntime.studyWindows.currentWindow()?.id

        val cost = DopaRuntime.overrideCost(rule)
        val balance = DopaRuntime.points.currentBalance()
        val rotated = DopaRuntime.rotate(pkg, ruleId, reflection, "いま開く必要はある?")

        // 逃げ道の有無と値段をキーに含める。ブロック画面を出したあとに予定が始まったり
        // 残高が変わったりしたら、同じルールでも出し直して表示を合わせる必要がある。
        val key = "$pkg|block|$ruleId|${if (canOverride) "o" else "x"}|$cost|$balance"
        if (overlay.currentKey == key) return

        DopaRuntime.scope.launch {
            activeLogId = DopaRuntime.stats.recordBlockShown(pkg, ruleId, ruleName, actionId)
        }

        val label = appLabel(pkg)
        overlay.show(key, OverlayMode.BLOCKING, coverSystemBars) {
            BlockScreen(
                appLabel = label,
                ruleName = ruleName,
                reflection = rotated,
                minSeconds = minSeconds,
                allowOverride = canOverride,
                overrideCost = cost,
                balance = balance,
                penaltyNote = penaltyNote(rule),
                releaseEffort = rule.actionParams.string(
                    BlockAction.KEY_RELEASE_EFFORT,
                    BlockAction.Effort.TAP,
                ),
                rotationNote = if (Rotation.rotates(reflection)) Rotation.EXPLANATION else "",
                onAbortStudy = abortWindowId?.let { windowId ->
                    {
                        // 相手に伝えたあと、送り直しを待たずにこちらでも解く。
                        // 待っているあいだブロックが残ると、出口として機能しない。
                        StudyAbort.abort(this, windowId)
                        DopaRuntime.studyWindows.endNow(windowId)
                        overlay.hide()
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                },
                onDismiss = {
                    overlay.hide()
                    DopaRuntime.reward(rule)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                },
                onOverride = {
                    val logId = activeLogId
                    if (logId != null) {
                        DopaRuntime.scope.launch { DopaRuntime.stats.recordOverride(logId) }
                    }
                    // 罰を先に科してから猶予を置く。順番が逆だと、
                    // 罰で閉まる前に猶予が効いて素通りになる
                    DopaRuntime.punish(pkg, rule, violation)
                    overrideUntil[pkg] = System.currentTimeMillis() + OVERRIDE_GRACE_MS
                    overlay.hide()
                },
            )
        }
    }

    /** 押し切ったら何が閉まるか。押す前に見せるための1行。 */
    private fun penaltyNote(rule: Rule): String {
        val consequence = rule.consequence
        if (consequence.locksNothing) return ""
        return "押し切ると${consequence.lockScope.label}が${consequence.lockMinutes}分閉まります"
    }

    private fun showWarn(pkg: String, act: Decision.Act) {
        val repeatMs = act.params.int(WarnAction.KEY_REPEAT_MINUTES, 5) * 60_000L
        val lastShown = warnShownAt[act.rule.id] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastShown < repeatMs) return

        warnShownAt[act.rule.id] = now
        val seconds = act.params.int(WarnAction.KEY_SECONDS, 5)
        val message = act.params.string(WarnAction.KEY_MESSAGE).ifBlank { "そろそろやめる時間。" }
        val key = "$pkg|warn|${act.rule.id}"

        overlay.show(key, OverlayMode.PASS_THROUGH) { WarnScreen(message) }
        handler.postDelayed({ if (overlay.currentKey == key) overlay.hide() }, seconds * 1000L)

        DopaRuntime.scope.launch {
            DopaRuntime.stats.recordBlockShown(pkg, act.rule.id, act.rule.name, act.action.id)
        }

        scheduleIgnoreCheck(pkg, act)
    }

    /**
     * 警告を出したあと、まだ同じアプリに居座っていたら「破った」とみなす。
     *
     * 警告には押し切りボタンが無いので、居座り続けること自体が押し切りにあたる。
     * ここが無いと、いちばん弱い措置だけ罰の外に置かれることになる。
     *
     * 予約は取り消せるようにしてある。アプリを離れたのに、その後で
     * 罰だけ降ってくるのは筋が通らない。
     */
    private fun scheduleIgnoreCheck(pkg: String, act: Decision.Act) {
        val ignoreMinutes = act.params.int(WarnAction.KEY_IGNORE_MINUTES, 5)
        if (ignoreMinutes <= 0) return
        if ("${act.rule.id}|$pkg" in punishedWarnIgnores) return

        pendingIgnoreChecks.remove(act.rule.id)?.let { handler.removeCallbacks(it) }

        val check = Runnable {
            pendingIgnoreChecks.remove(act.rule.id)
            // まだ同じアプリを開いていて、ルールも成立したままなら無視したとみなす
            if (foregroundPackage != pkg || !DopaRuntime.screenOn) return@Runnable
            val still = DopaRuntime.decide(pkg)
            if (still is Decision.Act && still.rule.id == act.rule.id &&
                punishedWarnIgnores.add("${act.rule.id}|$pkg")
            ) {
                DopaRuntime.punish(pkg, act.rule, PointReason.WARN_IGNORED)
            }
        }
        pendingIgnoreChecks[act.rule.id] = check
        handler.postDelayed(check, ignoreMinutes * 60_000L)
    }

    private fun showDeclareOrPass(pkg: String, act: Decision.Act) {
        val remaining = DopaRuntime.declarations.remainingMinutes(pkg)

        when {
            // まだ宣言していない → 宣言させる
            remaining == null -> {
                // 宣言が切れた = 次の回。超過の印を落として数え直す
                punishedOverruns.remove("${pkg}|${act.rule.id}")
                val key = "$pkg|declare|${act.rule.id}"
                if (overlay.currentKey == key) return
                val label = appLabel(pkg)
                overlay.show(key, OverlayMode.BLOCKING) {
                    DeclareScreen(
                        appLabel = label,
                        maxMinutes = act.params.int(DeclareAction.KEY_MAX_MINUTES, 30),
                        defaultMinutes = act.params.int(DeclareAction.KEY_DEFAULT_MINUTES, 10),
                        requireReason = act.params.bool(DeclareAction.KEY_REQUIRE_REASON, false),
                        onDeclare = { minutes, reason ->
                            DopaRuntime.declarations.declare(pkg, minutes, reason)
                            overlay.hide()
                        },
                        onCancel = {
                            overlay.hide()
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        },
                    )
                }
            }

            // 宣言ぶんを使い切った → 破った扱いにして封印に切り替える。
            // 押し切りを待たずにここで罰するのは、超えた時点がすでに違反だから。
            // 二重に科さないよう、その宣言につき一度だけ。
            remaining <= 0 -> {
                if (punishedOverruns.add("${pkg}|${act.rule.id}")) {
                    DopaRuntime.punish(pkg, act.rule, PointReason.DECLARE_OVERRUN)
                }
                showBlock(
                    pkg = pkg,
                    rule = act.rule,
                    reflection = act.params.string(DeclareAction.KEY_REFLECTION)
                        .ifBlank { "宣言した時間は終わり。" },
                    minSeconds = 15,
                    coverSystemBars = true,
                    allowOverride = true,
                    actionId = act.action.id,
                    violation = PointReason.OVERRIDE,
                )
            }

            // まだ余裕がある
            else -> if (overlay.currentKey?.startsWith("$pkg|") == true) overlay.hide()
        }
    }

    /** ホーム画面に戻ったときの一時オーバーレイ。 */
    private fun maybeGreetOnHome() {
        DopaRuntime.scope.launch {
            if (!DopaRuntime.settings.blockHomeScreen.first()) return@launch
            val message = DopaRuntime.settings.unlockMessage.first()
            handler.post {
                val key = "home|greet"
                overlay.show(key, OverlayMode.PASS_THROUGH) { WarnScreen(message) }
                handler.postDelayed({ if (overlay.currentKey == key) overlay.hide() }, HOME_GREET_MS)
            }
        }
    }

    /** ロック解除の直後。待ち受け画面そのものには被せられないので、解除直後に出す。 */
    private fun onUserPresent() {
        DopaRuntime.scope.launch {
            if (!DopaRuntime.settings.showOnUnlock.first()) return@launch
            val message = DopaRuntime.settings.unlockMessage.first()
            handler.post {
                val key = "unlock|prompt"
                overlay.show(key, OverlayMode.BLOCKING) {
                    UnlockPromptScreen(message) { overlay.hide() }
                }
                handler.postDelayed({ if (overlay.currentKey == key) overlay.hide() }, UNLOCK_PROMPT_TIMEOUT_MS)
            }
        }
    }

    // ------------------------------------------------------------------

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }

    private fun resolveLauncherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun resolveImePackages(): Set<String> {
        val imm = getSystemService(InputMethodManager::class.java) ?: return emptySet()
        return runCatching {
            imm.enabledInputMethodList.mapNotNull { it.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun resolveSettingsPackages(): Set<String> {
        val resolved = packageManager
            .queryIntentActivities(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
        return resolved + "com.android.settings"
    }

    companion object {
        /**
         * 動いているサービス。無効化されていれば null。
         *
         * AccessibilityService はプロセスと寿命を共にするので保持してよい。
         * onDestroy で必ず外す。
         */
        @Volatile
        private var live: DopaAccessibilityService? = null

        /** 外部の出来事(学習予定の開始など)を受けて、判定をやり直させる。 */
        fun kickEvaluation() {
            live?.requestImmediateEvaluation()
        }

        /** これより短い間隔では見に来ない。 */
        private const val CHECK_FLOOR_MS = 3_000L

        /** 条件が「いつ変わるか分からない」と答えたときの間隔。 */
        private const val CHECK_CEILING_MS = 30_000L
        private const val CHECK_CEILING_SAVER_MS = 120_000L

        private const val OVERRIDE_GRACE_MS = 5 * 60_000L
        private const val HOME_GREET_MS = 2_500L
        private const val UNLOCK_PROMPT_TIMEOUT_MS = 8_000L
        private const val SELF_DEFENSE_SECONDS = 10
        private const val SELF_DEFENSE_SNOOZE_MS = 3 * 60_000L

        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
        )
    }
}
