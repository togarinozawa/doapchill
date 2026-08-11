package com.dopachiru.runtime

import android.content.Context
import android.os.PowerManager
import com.dopachiru.core.DopaCore
import com.dopachiru.core.condition.types.CalendarBusyCondition
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.data.CalendarReader
import com.dopachiru.data.ChangeRequestRepository
import com.dopachiru.data.DeclarationManager
import com.dopachiru.data.ProtectedApps
import com.dopachiru.data.RuleRepository
import com.dopachiru.data.SettingsStore
import com.dopachiru.data.StatsRepository
import com.dopachiru.data.StudyWindowRepository
import com.dopachiru.data.UsageTracker
import com.dopachiru.data.db.DopaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/**
 * アプリ全体で1つだけ持つ実行時の状態。
 *
 * ルール判定は AccessibilityService のコールバックから同期的に呼ばれるため、
 * ルールとタグはメモリ上のキャッシュから引く。DB は Flow で流し込むだけ。
 */
object DopaRuntime {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val engine = RuleEngine()

    @Volatile
    private var initialized = false

    private lateinit var powerManager: PowerManager

    lateinit var db: DopaDatabase
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var rules: RuleRepository
        private set
    lateinit var usage: UsageTracker
        private set
    lateinit var declarations: DeclarationManager
        private set
    lateinit var stats: StatsRepository
        private set
    lateinit var changes: ChangeRequestRepository
        private set
    lateinit var calendarReader: CalendarReader
        private set
    lateinit var studyWindows: StudyWindowRepository
        private set

    /** 何があってもブロックしないアプリ。ルールより強い。 */
    private lateinit var protectedApps: ProtectedApps

    @Volatile
    private var ruleCache: List<Rule> = emptyList()

    @Volatile
    private var tagCache: Map<String, Set<String>> = emptyMap()

    /** カレンダーを見るルールかゲートが1つでもあるか。無ければ読みにいかない。 */
    @Volatile
    private var calendarNeeded = false

    @Volatile
    var currentForegroundPackage: String? = null
        private set

    /** 画面が点いているか。消えているあいだは使用時間を数えないし、判定もしない。 */
    @Volatile
    var screenOn: Boolean = true
        private set

    /** 電池を優先する設定にしているか。判定の間隔が伸びる。 */
    @Volatile
    var batterySaverMode: Boolean = false
        private set

    private var lastCalendarRefreshMs = 0L
    private var lastWrittenScreenMinutes = -1

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }

        DopaCore.registerAll()

        val app = context.applicationContext
        powerManager = app.getSystemService(PowerManager::class.java)
        protectedApps = ProtectedApps(app)
        db = DopaDatabase.get(app)
        settings = SettingsStore(app)
        rules = RuleRepository(db.ruleDao(), db.appTagDao())
        usage = UsageTracker(db.usageDao(), scope)
        declarations = DeclarationManager(db.declarationDao(), scope)
        stats = StatsRepository(db.dayStatDao(), db.blockLogDao())
        calendarReader = CalendarReader(app)
        studyWindows = StudyWindowRepository(db.studyWindowDao(), scope)
        changes = ChangeRequestRepository(
            dao = db.changeRequestDao(),
            ruleRepository = rules,
            calendarState = { calendarReader.state() },
        )

        scope.launch {
            // 同期を始める前に、uid の無い古いルールへ振っておく
            rules.backfillUids()
            usage.warmUp()
            declarations.warmUp()
            // 再起動をまたいでも学習中のままでいられるように、窓を読み直す
            studyWindows.warmUp()
            usage.purgeOld()
            stats.ensureToday()
        }
        scope.launch {
            rules.rules.collect {
                ruleCache = it
                recomputeCalendarNeed()
            }
        }
        scope.launch { rules.tagsByPackage.collect { tagCache = it } }
        scope.launch {
            settings.gates.collect {
                gateCache = it
                recomputeCalendarNeed()
            }
        }
        scope.launch { settings.batterySaver.collect { batterySaverMode = it } }
        scope.launch { settings.studyPrepMinutes.collect { studyWindows.prepMinutes = it } }
    }

    @Volatile
    private var gateCache: List<Gate> = emptyList()

    private fun recomputeCalendarNeed() {
        val usedByRule = ruleCache.any { it.enabled && usesCalendar(it.condition) }
        val usedByGate = gateCache.any { it is Gate.CalendarWindow }
        calendarNeeded = usedByRule || usedByGate
        if (calendarNeeded) refreshCalendarIfStale(force = true)
    }

    private fun usesCalendar(node: ConditionNode): Boolean = when (node) {
        is ConditionNode.Leaf -> node.typeId == CalendarBusyCondition.id
        is ConditionNode.AllOf -> node.children.any { usesCalendar(it) }
        is ConditionNode.AnyOf -> node.children.any { usesCalendar(it) }
        is ConditionNode.Not -> usesCalendar(node.child)
    }

    // ------------------------------------------------------------------

    fun onForegroundChanged(packageName: String?) {
        currentForegroundPackage = packageName
        usage.onForegroundChanged(packageName)
    }

    /**
     * 画面が消えた。
     *
     * 使用中のセッションをここで閉じる。閉じないと、寝ているあいだの時間が
     * まるごとアプリの使用時間になり、宣言した持ち時間も勝手に減っていく。
     */
    fun onScreenOff() {
        screenOn = false
        usage.onForegroundChanged(null)
    }

    /** 画面が点いた。前面のアプリは、続けて来る検知イベントで入り直る。 */
    fun onScreenOn() {
        screenOn = true
        refreshCalendarIfStale()
    }

    fun onPowerSaveModeChanged() {
        // PowerManager から都度読むので、キャッシュの更新は不要
    }

    private fun isDevicePowerSaving(): Boolean =
        runCatching { powerManager.isPowerSaveMode }.getOrDefault(false)

    // ------------------------------------------------------------------

    private fun buildContext(packageName: String, now: LocalDateTime) = EvalContext(
        now = now,
        packageName = packageName,
        usage = usage.snapshotFor(packageName, now),
        calendar = if (calendarNeeded) calendarReader.state() else com.dopachiru.core.engine.CalendarState.NONE,
        study = studyWindows.state(),
        powerSaveMode = isDevicePowerSaving(),
        declaredRemainingMinutes = declarations.remainingMinutes(packageName),
    )

    /**
     * 何があってもブロックしないアプリか。
     * 電話・ホーム・設定・入力メソッド・ドパチル自身。ルールより強い。
     */
    fun isProtected(packageName: String): Boolean =
        initialized && packageName in protectedApps

    /** そのアプリを、いまどう扱うべきか。 */
    fun decide(packageName: String, now: LocalDateTime = LocalDateTime.now()): Decision {
        if (!initialized) return Decision.Allow
        if (packageName in protectedApps) return Decision.Allow
        return engine.decide(ruleCache, buildContext(packageName, now)) { tagCache[it] ?: emptySet() }
    }

    /** 何らかのルールが対象にしているアプリか。監視の当たり判定を安く済ませるため。 */
    fun isTargeted(packageName: String): Boolean {
        if (initialized && packageName in protectedApps) return false
        val tags = tagCache[packageName] ?: emptySet()
        return ruleCache.any { it.enabled && it.target.matches(packageName, tags) }
    }

    /** 学習予定の最中か。押し切りを止めるかどうかの判断に使う。 */
    fun studyInSession(): Boolean = initialized && studyWindows.inSession()

    /**
     * 次に判定を見に来るまでの待ち時間。
     *
     * 条件が「この時刻までは変わらない」と答えられるぶんだけ長く眠る。
     * 答えられない条件が混じっていたら安全側に倒して [ceilMs] で見に来る。
     */
    fun nextCheckDelayMs(packageName: String, floorMs: Long, ceilMs: Long): Long {
        if (!initialized) return ceilMs
        val now = LocalDateTime.now()
        val at = engine.nextChangeAt(ruleCache, buildContext(packageName, now)) {
            tagCache[it] ?: emptySet()
        } ?: return ceilMs
        val ms = Duration.between(now, at).toMillis()
        return ms.coerceIn(floorMs, ceilMs)
    }

    // ------------------------------------------------------------------

    /**
     * 常駐サービスから呼ばれる定期処理。次に呼ぶまでの待ち時間を返す。
     *
     * 画面が消えているあいだは数えるものが何もないので、何もせず長く眠る。
     */
    fun tick(): Long {
        if (!initialized) return IDLE_TICK_MS
        if (!screenOn) return IDLE_TICK_MS

        val foreground = currentForegroundPackage
        usage.tick()
        declarations.tick(foreground)
        refreshCalendarIfStale()

        scope.launch {
            stats.ensureToday()
            val minutes = usage.totalMinutesIn(ResetPolicy())
            // 値が動いていないのに毎分書きに行かない
            if (minutes != lastWrittenScreenMinutes) {
                lastWrittenScreenMinutes = minutes
                stats.updateTotalScreenMinutes(minutes)
            }
        }
        return if (batterySaverMode) SAVER_TICK_MS else ACTIVE_TICK_MS
    }

    /**
     * カレンダーを読み直す。
     * 予定は分単位で動くものではないので、必要なときに数分おきで足りる。
     */
    private fun refreshCalendarIfStale(force: Boolean = false) {
        if (!calendarNeeded) return
        val now = System.currentTimeMillis()
        val interval = if (batterySaverMode) CALENDAR_SAVER_MS else CALENDAR_ACTIVE_MS
        if (!force && now - lastCalendarRefreshMs < interval) return
        lastCalendarRefreshMs = now
        scope.launch { calendarReader.refresh() }
    }

    /** 設定画面のプレビュー用。設定の状態に関わらず読みにいく。 */
    fun refreshCalendarNow() {
        lastCalendarRefreshMs = System.currentTimeMillis()
        scope.launch { calendarReader.refresh() }
    }

    private const val ACTIVE_TICK_MS = 60_000L
    private const val SAVER_TICK_MS = 180_000L
    private const val IDLE_TICK_MS = 600_000L
    private const val CALENDAR_ACTIVE_MS = 5 * 60_000L
    private const val CALENDAR_SAVER_MS = 15 * 60_000L
}
