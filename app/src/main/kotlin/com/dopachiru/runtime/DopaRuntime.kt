package com.dopachiru.runtime

import android.content.Context
import android.os.PowerManager
import com.dopachiru.core.DopaCore
import com.dopachiru.core.DopaFeatures
import com.dopachiru.core.action.Rotation
import com.dopachiru.core.condition.types.CalendarBusyCondition
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.Rule
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.core.points.PointReason
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.data.CalendarReader
import com.dopachiru.data.ChangeRequestRepository
import com.dopachiru.data.DeclarationManager
import com.dopachiru.data.LockoutRepository
import com.dopachiru.data.PointsRepository
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
import java.time.LocalDate
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
    lateinit var lockouts: LockoutRepository
        private set
    lateinit var points: PointsRepository
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

    /** ポイントの使い道と相場。判定から同期的に読むのでキャッシュする。 */
    @Volatile
    var pointPolicy: PointPolicy = PointPolicy.DEFAULT
        private set

    /** 解禁券で制限が止まっている期限(秒)。過ぎれば勝手に戻る。 */
    @Volatile
    private var passUntilSec: Long = 0L

    private var lastCalendarRefreshMs = 0L
    private var lastWrittenScreenMinutes = -1

    /**
     * 判定に使う時刻をずらす(分)。開発ツール専用で、既定は 0。
     *
     * 「22時以降は封印」を昼間に試すために要る。実際に夜まで待つのは検証にならない。
     * 記録そのものは実時刻で残るので、ここを大きくずらすと集計期間との噛み合わせが
     * ずれる ── 使用時間の条件を試すときは、開発ツールから直接盛るほうが確実。
     */
    @Volatile
    var devClockOffsetMinutes: Int = 0

    /** 判定に使う「いま」。開発ツールでずらせる以外は普通の現在時刻。 */
    fun now(): LocalDateTime =
        LocalDateTime.now().plusMinutes(devClockOffsetMinutes.toLong())

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
        lockouts = LockoutRepository(db.lockoutDao(), scope)
        points = PointsRepository(db.pointEventDao(), scope)
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
            // 罰と残高も同じ。再起動で罰が消えるなら罰にならない
            lockouts.warmUp()
            points.warmUp()
            overrideCounts = stats.overrideCountsByRule()
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
        scope.launch { settings.pointPolicy.collect { pointPolicy = it } }
        scope.launch { settings.passUntilEpochSec.collect { passUntilSec = it } }
    }

    @Volatile
    private var gateCache: List<Gate> = emptyList()

    private fun recomputeCalendarNeed() {
        // 凍結中は、使っているルールが残っていても読みに行かない
        if (!DopaFeatures.CALENDAR_ENABLED) {
            calendarNeeded = false
            return
        }
        val usedByRule = ruleCache.any { it.enabled && usesCalendar(it.condition) }
        val usedByGate = gateCache.any { it is Gate.CalendarWindow }
        calendarNeeded = usedByRule || usedByGate
        if (calendarNeeded) refreshCalendarIfStale(force = true)
    }

    /** そのルールが、凍結中の機能に頼っていて動かないか。編集画面で知らせるため。 */
    fun usesFrozenFeature(rule: Rule): Boolean =
        !DopaFeatures.CALENDAR_ENABLED && usesCalendar(rule.condition)

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

    /**
     * ルールごとの押し切り回数(直近1週間)。慣れの判定に使う。
     * 定期処理で入れ替える。判定から同期的に読むのでキャッシュしている。
     */
    @Volatile
    private var overrideCounts: Map<Long, Int> = emptyMap()

    private fun buildContext(packageName: String, now: LocalDateTime) = EvalContext(
        now = now,
        packageName = packageName,
        usage = usage.snapshotFor(packageName, now),
        calendar = if (calendarNeeded) calendarReader.state() else com.dopachiru.core.engine.CalendarState.NONE,
        study = studyWindows.state(),
        powerSaveMode = isDevicePowerSaving(),
        declaredRemainingMinutes = declarations.remainingMinutes(packageName),
        previousPackage = usage.previousPackage(),
        sessionSeed = usage.currentSessionSeed(),
        overrideCountOf = { ruleId -> overrideCounts[ruleId] ?: 0 },
    )

    /**
     * 何があってもブロックしないアプリか。
     * 電話・ホーム・設定・入力メソッド・ドパチル自身。ルールより強い。
     */
    fun isProtected(packageName: String): Boolean =
        initialized && packageName in protectedApps

    /** そのアプリを、いまどう扱うべきか。 */
    fun decide(packageName: String, now: LocalDateTime = now()): Decision {
        if (!initialized) return Decision.Allow
        if (packageName in protectedApps) return Decision.Allow
        val nowSec = System.currentTimeMillis() / 1000
        return engine.decide(
            rules = ruleCache,
            lockouts = lockouts.current(nowSec),
            ctx = buildContext(packageName, now),
            nowSec = nowSec,
            passUntilSec = passUntilSec,
        ) { tagCache[it] ?: emptySet() }
    }

    /** 解禁券が効いているあいだの期限(秒)。効いていなければ 0。 */
    fun passUntil(): Long = passUntilSec.takeIf { System.currentTimeMillis() / 1000 < it } ?: 0L

    // ------------------------------------------------------------------
    // 破った / 守ったときに起きること

    /**
     * ルールを破った。罰を科し、ポイントを引く。
     *
     * 封鎖は罰を科した時点の範囲で固定する。あとからルールを書き換えても
     * 罰の重さが変わらないようにするため。
     */
    fun punish(packageName: String, rule: Rule, reason: PointReason) {
        if (!initialized) return
        val consequence = rule.consequence

        consequence.resolveTarget(packageName, rule.target)?.let { target ->
            // 段階を切ってあれば、直近24時間に同じルールで科した回数だけ長くなる。
            // 1回目から重くしないのは、強い制約は目標そのものを緩めさせるため
            val repeats = if (consequence.lockEscalates) lockouts.recentCountFor(rule.name) else 0
            lockouts.impose(
                target = target,
                minutes = consequence.lockMinutesFor(repeats),
                reason = rule.name,
            )
        }

        val delta = pointPolicy.breakDelta(consequence.breakPoints)
        if (pointPolicy.enabled && delta != 0) {
            points.record(
                delta = delta,
                reason = reason,
                note = rule.name,
                floor = pointPolicy.floor,
            )
        }
    }

    /** ブロック画面から引き返した。 */
    fun reward(rule: Rule) {
        if (!initialized || !pointPolicy.enabled) return
        val delta = pointPolicy.keepDelta(rule.consequence.keepPoints)
        if (delta != 0) points.record(delta, PointReason.BACKED_OFF, rule.name)
    }

    /** 押し切るのにいくら要るか。0 なら代金は取らない。 */
    fun overrideCost(rule: Rule): Int = pointPolicy.overrideCost(rule.consequence.breakPoints)

    /** その値段を払えるか。払えないと押し切りボタンが出ない。 */
    fun canAfford(cost: Int): Boolean = cost <= 0 || points.currentBalance() >= cost

    /**
     * 解禁券を買う。買えたら true。
     *
     * 期限を持たせてあるので、買ったまま解除を忘れて縛りが死ぬことがない。
     */
    suspend fun buyPass(): Boolean {
        if (!initialized) return false
        val policy = pointPolicy
        if (!policy.enabled || !policy.passEnabled) return false
        if (points.currentBalance() < policy.passCost) return false

        points.record(-policy.passCost, PointReason.PASS_BOUGHT, "${policy.passMinutes}分")
        val until = System.currentTimeMillis() / 1000 + policy.passMinutes * 60L
        settings.setPassUntil(until)
        passUntilSec = until
        return true
    }

    /** 何らかのルールが対象にしているアプリか。監視の当たり判定を安く済ませるため。 */
    fun isTargeted(packageName: String): Boolean {
        if (initialized && packageName in protectedApps) return false
        val tags = tagCache[packageName] ?: emptySet()
        if (ruleCache.any { it.enabled && it.target.matches(packageName, tags) }) return true
        // 罰で閉まっているアプリも見に行く。ルールが狙っていない範囲まで閉める罰
        // (「逃がすもの以外ぜんぶ」など)があるので、ここを落とすと罰が効かない
        return initialized &&
            lockouts.current().any { it.target.matches(packageName, tags) }
    }

    /** 学習予定の最中か。押し切りを止めるかどうかの判断に使う。 */
    fun studyInSession(): Boolean = initialized && studyWindows.inSession()

    /** いま開いているセッションの種。画面のキーに混ぜて、開き直しを見分けるため。 */
    fun sessionSeed(): Long = if (initialized) usage.currentSessionSeed() else 0L

    /**
     * 改行で分けた候補から1つ選ぶ。同じ使用のあいだは同じものが返る。
     *
     * 同じ文が続くと慣れる(固定の介入は露出1日ごとに効果25%減)。
     * 判定と同じ種を使うので、画面の出し直しで文が入れ替わることもない。
     */
    fun rotate(packageName: String, ruleId: Long, text: String, fallback: String): String {
        if (!initialized) return text.ifBlank { fallback }
        val ctx = EvalContext(
            now = now(),
            packageName = packageName,
            usage = com.dopachiru.core.engine.UsageSnapshot.EMPTY,
            sessionSeed = usage.currentSessionSeed(),
            currentRuleId = ruleId,
        )
        return Rotation.pick(text, ctx, fallback)
    }

    // ------------------------------------------------------------------
    // 開発ツールから使うもの。ふだんの動作には関わらない。

    /**
     * いまこのアプリに対して、どのルールがどう判定されるか。
     *
     * ブロックが出ない・出すぎるときに「どの条件で落ちているか」を見るため。
     * 判定と同じ [EvalContext] を通すので、画面の表示と実際の挙動がずれない。
     */
    fun explain(packageName: String): List<RuleVerdict> {
        if (!initialized) return emptyList()
        val ctx = buildContext(packageName, now())
        val tags = tagCache[packageName] ?: emptySet()
        return ruleCache.map { rule ->
            RuleVerdict(
                ruleName = rule.name,
                enabled = rule.enabled,
                targeted = rule.target.matches(packageName, tags),
                conditionMet = engine.evaluate(rule.condition, ctx),
            )
        }
    }

    data class RuleVerdict(
        val ruleName: String,
        val enabled: Boolean,
        val targeted: Boolean,
        val conditionMet: Boolean,
    ) {
        val fires: Boolean get() = enabled && targeted && conditionMet

        /** 成立しない理由。表示用。 */
        val reason: String
            get() = when {
                fires -> "成立"
                !enabled -> "無効"
                !targeted -> "対象外"
                else -> "条件を満たさない"
            }
    }

    /** 学習予定をでっちあげる。連携アプリ無しで、学習中・助走枠・中断を試すため。 */
    fun devFakeStudyWindow(startsInMinutes: Int, lengthMinutes: Int) {
        val nowSec = System.currentTimeMillis() / 1000
        studyWindows.replaceAll(
            listOf(
                StudyWindowRepository.Window(
                    id = "dev-" + nowSec,
                    startSec = nowSec + startsInMinutes * 60L,
                    endSec = nowSec + (startsInMinutes + lengthMinutes) * 60L,
                    title = "開発用の予定",
                    kind = "study",
                )
            )
        )
    }

    fun devClearStudyWindows() = studyWindows.replaceAll(emptyList())

    /** 罰を1つ科す。封鎖画面と、解けたあとの戻りを確かめるため。 */
    fun devImposeLockout(minutes: Int, everything: Boolean) {
        val target = if (everything) {
            com.dopachiru.core.model.Target(matchAll = true)
        } else {
            com.dopachiru.core.model.Target(
                packages = setOfNotNull(currentForegroundPackage)
            )
        }
        lockouts.impose(target, minutes, "開発ツールから")
    }

    fun devClearLockouts() = lockouts.clearAll()

    fun devAddPoints(delta: Int) = points.record(delta, PointReason.MANUAL, "開発ツール")

    fun devClearPoints() = points.clearAll()

    /**
     * 次に判定を見に来るまでの待ち時間。
     *
     * 条件が「この時刻までは変わらない」と答えられるぶんだけ長く眠る。
     * 答えられない条件が混じっていたら安全側に倒して [ceilMs] で見に来る。
     */
    fun nextCheckDelayMs(packageName: String, floorMs: Long, ceilMs: Long): Long {
        if (!initialized) return ceilMs
        val now = now()

        // 罰が解ける時刻。ここで起きないと、時間が過ぎても画面が開かない
        val tags = tagCache[packageName] ?: emptySet()
        val lockLiftsInMs = lockouts.current()
            .filter { it.target.matches(packageName, tags) }
            .minOfOrNull { it.untilEpochSec }
            ?.let { it * 1000 - System.currentTimeMillis() }

        val at = engine.nextChangeAt(ruleCache, buildContext(packageName, now)) {
            tagCache[it] ?: emptySet()
        }
        val ruleChangeInMs = at?.let { Duration.between(now, it).toMillis() }

        val ms = listOfNotNull(lockLiftsInMs, ruleChangeInMs).minOrNull() ?: return ceilMs
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
        lockouts.purgeExpired()
        refreshCalendarIfStale()
        awardStudyIfCompleted()

        scope.launch {
            stats.ensureToday()
            overrideCounts = stats.overrideCountsByRule()
            awardCleanDayIfDue()
            val minutes = usage.totalMinutesIn(ResetPolicy())
            // 値が動いていないのに毎分書きに行かない
            if (minutes != lastWrittenScreenMinutes) {
                lastWrittenScreenMinutes = minutes
                stats.updateTotalScreenMinutes(minutes)
            }
        }
        return if (batterySaverMode) SAVER_TICK_MS else ACTIVE_TICK_MS
    }

    /** 学習予定を完走していたら加点する。中断したものは対象外。 */
    private fun awardStudyIfCompleted() {
        val policy = pointPolicy
        if (!policy.enabled || policy.studyDonePoints == 0) return
        val windowId = studyWindows.takeCompletedWindowId() ?: return
        points.record(
            delta = policy.studyDonePoints,
            reason = PointReason.STUDY_DONE,
            dedupKey = "study:$windowId",
        )
    }

    /**
     * 昨日を押し切りゼロで終えていたら加点する。
     *
     * 誘惑が一度も無かった日まで加点すると、端末を触らなかっただけで貯まる。
     * ブロックが1度は出た日に限る。
     */
    private suspend fun awardCleanDayIfDue() {
        val policy = pointPolicy
        if (!policy.enabled || policy.cleanDayPoints == 0) return
        val yesterday = LocalDate.now().toEpochDay() - 1
        val stat = stats.dayStat(yesterday) ?: return
        if (!stat.kept || stat.blockShownCount == 0) return
        points.record(
            delta = policy.cleanDayPoints,
            reason = PointReason.CLEAN_DAY,
            dedupKey = "cleanday:$yesterday",
        )
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

    /** 設定画面のプレビュー用。設定の状態に関わらず読みにいく(凍結中は何もしない)。 */
    fun refreshCalendarNow() {
        if (!DopaFeatures.CALENDAR_ENABLED) return
        lastCalendarRefreshMs = System.currentTimeMillis()
        scope.launch { calendarReader.refresh() }
    }

    private const val ACTIVE_TICK_MS = 60_000L
    private const val SAVER_TICK_MS = 180_000L
    private const val IDLE_TICK_MS = 600_000L
    private const val CALENDAR_ACTIVE_MS = 5 * 60_000L
    private const val CALENDAR_SAVER_MS = 15 * 60_000L
}
