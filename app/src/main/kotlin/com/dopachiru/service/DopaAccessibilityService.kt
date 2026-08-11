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
import com.dopachiru.block.OverlayHost
import com.dopachiru.block.OverlayMode
import com.dopachiru.block.SelfDefenseScreen
import com.dopachiru.block.UnlockPromptScreen
import com.dopachiru.block.WarnScreen
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.engine.Decision
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
        // 学習予定の最中は押し切りの猶予を効かせない。
        // 予定が始まる前に押し切っておいて、そのまま持ち込むのを防ぐ。
        if (!DopaRuntime.studyInSession() &&
            System.currentTimeMillis() < (overrideUntil[pkg] ?: 0L)
        ) return

        when (val decision = DopaRuntime.decide(pkg)) {
            is Decision.Allow -> if (overlay.currentKey?.startsWith("$pkg|") == true) overlay.hide()
            is Decision.Act -> present(pkg, decision)
        }
    }

    private fun present(pkg: String, act: Decision.Act) {
        when (act.action.id) {
            BlockAction.id -> showBlock(
                pkg = pkg,
                ruleId = act.rule.id,
                ruleName = act.rule.name,
                reflection = act.params.string(BlockAction.KEY_REFLECTION),
                minSeconds = act.params.int(BlockAction.KEY_MIN_SECONDS, 15),
                coverSystemBars = act.params.bool(BlockAction.KEY_COVER_SYSTEM_BARS, true),
                allowOverride = act.params.bool(BlockAction.KEY_ALLOW_OVERRIDE, true),
                actionId = act.action.id,
            )

            WarnAction.id -> showWarn(pkg, act)
            DeclareAction.id -> showDeclareOrPass(pkg, act)
            else -> Unit
        }
    }

    private fun showBlock(
        pkg: String,
        ruleId: Long,
        ruleName: String,
        reflection: String,
        minSeconds: Int,
        coverSystemBars: Boolean,
        allowOverride: Boolean,
        actionId: String,
    ) {
        // 学習予定の最中は、ルールの設定に関わらず押し切れない
        val canOverride = allowOverride && !DopaRuntime.studyInSession()

        // 逃げ道の有無をキーに含める。ブロック画面を出したあとに予定が始まったら、
        // 同じルールでも画面を出し直してボタンを消す必要がある。
        val key = "$pkg|block|$ruleId|${if (canOverride) "o" else "x"}"
        if (overlay.currentKey == key) return

        DopaRuntime.scope.launch {
            activeLogId = DopaRuntime.stats.recordBlockShown(pkg, ruleId, ruleName, actionId)
        }

        val label = appLabel(pkg)
        overlay.show(key, OverlayMode.BLOCKING, coverSystemBars) {
            BlockScreen(
                appLabel = label,
                ruleName = ruleName,
                reflection = reflection,
                minSeconds = minSeconds,
                allowOverride = canOverride,
                onDismiss = {
                    overlay.hide()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                },
                onOverride = {
                    val logId = activeLogId
                    if (logId != null) {
                        DopaRuntime.scope.launch { DopaRuntime.stats.recordOverride(logId) }
                    }
                    overrideUntil[pkg] = System.currentTimeMillis() + OVERRIDE_GRACE_MS
                    overlay.hide()
                },
            )
        }
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
    }

    private fun showDeclareOrPass(pkg: String, act: Decision.Act) {
        val remaining = DopaRuntime.declarations.remainingMinutes(pkg)

        when {
            // まだ宣言していない → 宣言させる
            remaining == null -> {
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

            // 宣言ぶんを使い切った → 封印に切り替える
            remaining <= 0 -> showBlock(
                pkg = pkg,
                ruleId = act.rule.id,
                ruleName = act.rule.name,
                reflection = act.params.string(DeclareAction.KEY_REFLECTION)
                    .ifBlank { "宣言した時間は終わり。" },
                minSeconds = 15,
                coverSystemBars = true,
                allowOverride = true,
                actionId = act.action.id,
            )

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
