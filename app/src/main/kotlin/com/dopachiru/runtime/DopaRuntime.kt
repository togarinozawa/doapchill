package com.dopachiru.runtime

import android.content.Context
import android.os.PowerManager
import com.dopachiru.core.DopaCore
import com.dopachiru.core.DopaFeatures
import com.dopachiru.core.action.Rotation
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.condition.types.CalendarBusyCondition
import com.dopachiru.core.engine.Decision
import com.dopachiru.core.engine.EvalContext
import com.dopachiru.core.engine.RuleEngine
import com.dopachiru.core.engine.WindowUsage
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.model.Command
import com.dopachiru.core.model.CommandKind
import com.dopachiru.core.model.CommandState
import com.dopachiru.core.model.CommandVerdict
import com.dopachiru.core.model.Commands
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.DeviceScope
import com.dopachiru.core.model.FocusSchedule
import com.dopachiru.core.model.FocusSchedules
import com.dopachiru.core.model.FocusSettings
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Lockouts
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.Rule
import com.dopachiru.core.param.Params
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.core.points.PointReason
import android.os.Build
import com.dopachiru.BuildConfig
import com.dopachiru.core.sync.DeviceInfo
import com.dopachiru.core.sync.Enrollment
import com.dopachiru.core.sync.SyncApi
import com.dopachiru.core.sync.RuleState
import com.dopachiru.core.sync.RuleStates
import com.dopachiru.core.model.RuleLinks
import com.dopachiru.core.sync.SyncKinds
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.data.CalendarReader
import com.dopachiru.data.ChangeRequestRepository
import com.dopachiru.data.DeclarationManager
import com.dopachiru.data.LockoutRepository
import com.dopachiru.data.PointsRepository
import com.dopachiru.data.ProtectedApps
import com.dopachiru.data.ReservationRepository
import com.dopachiru.data.RuleRepository
import com.dopachiru.data.SettingsStore
import com.dopachiru.data.SyncManager
import com.dopachiru.service.DopaAccessibilityService
import com.dopachiru.data.StatsRepository
import com.dopachiru.data.StudyWindowRepository
import com.dopachiru.data.UsageTracker
import com.dopachiru.data.db.DopaDatabase
import com.dopachiru.focus.FocusWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

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

    /** ホーム画面のウィジェットを描き直すために持つ。判定には使わない。 */
    private lateinit var appContext: Context

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

    lateinit var reservations: ReservationRepository
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

    /** 集中モードの既定値。判定から同期的に読むのでキャッシュする。 */
    @Volatile
    var focusSettings: FocusSettings = FocusSettings()
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
        appContext = app
        powerManager = app.getSystemService(PowerManager::class.java)
        protectedApps = ProtectedApps(app)
        db = DopaDatabase.get(app)
        settings = SettingsStore(app)
        rules = RuleRepository(db.ruleDao(), db.appTagDao(), db.syncStateDao())
        usage = UsageTracker(db.usageDao(), scope)
        declarations = DeclarationManager(db.declarationDao(), scope)
        stats = StatsRepository(db.dayStatDao(), db.blockLogDao())
        calendarReader = CalendarReader(app)
        studyWindows = StudyWindowRepository(db.studyWindowDao(), scope)
        lockouts = LockoutRepository(db.lockoutDao(), scope)
        reservations = ReservationRepository(settings, scope)
        points = PointsRepository(db.pointEventDao(), scope)
        sync = SyncManager(app, rules, stats, db.syncStateDao(), settings)
        // 予約を変えたら、同期の「いつ変えたか」も一緒に動かす
        reservations.onChanged = { uid, deleted ->
            if (deleted) {
                sync.tombstone(SyncKinds.RESERVATIONS, uid)
            } else {
                sync.touch(SyncKinds.RESERVATIONS, uid)
            }
        }
        changes = ChangeRequestRepository(
            dao = db.changeRequestDao(),
            ruleRepository = rules,
            calendarState = { calendarReader.state() },
        )

        scope.launch {
            // 同期を始める前に、uid の無い古いルールへ振っておく
            rules.backfillUids()
            // その場で決めた枠のうち、期限が来たものを落とす。
            // 起動時にやるのは、寝ているあいだに明けるのがふつうだから
            runCatching { rules.purgeExpired() }
            usage.warmUp()
            declarations.warmUp()
            // 再起動をまたいでも学習中のままでいられるように、窓を読み直す
            studyWindows.warmUp()
            // 罰と残高も同じ。再起動で罰が消えるなら罰にならない
            lockouts.warmUp()
            reservations.warmUp()
            points.warmUp()
            overrideCounts = stats.overrideCountsByRule()
            usage.purgeOld()
            stats.ensureToday()
        }
        scope.launch {
            rules.rules.collect {
                ruleCache = it
                recomputeRuleScope()
                recomputeCalendarNeed()
            }
        }
        scope.launch {
            settings.syncSettings.collect {
                myDeviceId = it.deviceId
                recomputeRuleScope()
            }
        }
        enrollIfNeeded()
        scope.launch { rules.tagsByPackage.collect { tagCache = it } }
        scope.launch { settings.ruleStates.collect { ruleStateCache = it } }
        scope.launch {
            settings.gates.collect {
                gateCache = it
                recomputeCalendarNeed()
            }
        }
        scope.launch { settings.batterySaver.collect { batterySaverMode = it } }
        scope.launch { settings.studyPrepMinutes.collect { studyWindows.prepMinutes = it } }
        scope.launch { settings.pointPolicy.collect { pointPolicy = it } }
        scope.launch { settings.focusSettings.collect { focusSettings = it } }
        scope.launch { settings.focusSchedules.collect { focusSchedules = it } }
        scope.launch { settings.focusScheduleRuns.collect { scheduleRuns = it } }
        scope.launch { settings.passUntilEpochSec.collect { passUntilSec = it } }
    }

    /**
     * まだ繋いでいなければ、自分で名簿に載りにいく。
     *
     * ## なぜ手で合言葉を入れさせないのか
     *
     * 使うのが一人だからです。端末を足すたびに48文字を写すより、入れた直後から
     * 同じルールが載っているほうが、実際に使う形に近い。
     *
     * ## 何を引き換えにしているか
     *
     * 入口の鍵は APK の中にあり、APK は公開の場に置いてあります。**中を開けた人は
     * ここを叩けます。** 代わりにサーバーが配るのは端末ごとに別の合言葉なので、
     * 名簿に見慣れない名前が出たら、その1台だけ止められます。[Enrollment]
     *
     * ## 失敗しても黙ります
     *
     * 圏外で起動しただけで画面に赤字が出るのは行儀が悪い。次の起動でまた試します。
     * **繋がらなくても制限は効いたまま**なので、急ぐ理由もありません。
     */
    private fun enrollIfNeeded() {
        val key = BuildConfig.ENROLL_KEY
        scope.launch {
            val current = settings.syncSettings.first()
            if (!Enrollment.needed(current, key)) return@launch

            val name = settings.deviceName.first().ifBlank { Build.MODEL.orEmpty().ifBlank { "Android" } }
            // deviceId は実績の見出しでもあるので、一度決めたら変えない
            val deviceId = current.deviceId.ifBlank {
                "android-" + UUID.randomUUID().toString().take(8)
            }

            val result = withContext(Dispatchers.IO) {
                Enrollment.run(current, key, deviceId, name, "android")
            }
            if (result is Enrollment.Result.Ok) {
                settings.setSyncSettings(result.settings)
                if (settings.deviceName.first().isBlank()) settings.setDeviceName(name)
            }
        }
    }

    /**
     * 「そのルールが、その端末で、いま効いているか」。判定から同期的に読むので持っておく。
     *
     * 自分のぶんも入っているが、読むときに自分は外す ── 同じルールが端末をまたいで
     * 同じ uid を持つので、外さないと自分の状態を自分で読んで永久に外れない。
     */
    @Volatile
    private var ruleStateCache: List<RuleState> = emptyList()

    /** この端末の deviceId。同期を設定していなければ空。 */
    @Volatile
    var myDeviceId: String = ""
        private set

    /**
     * この端末で評価に載せるルールだけ。
     *
     * 別の端末に向けて書かれたものは**載せません**。載せたうえで無視すると、
     * 慣れの数え方や持ち時間の窓が端末ごとにずれます。[DeviceScope]
     */
    @Volatile
    private var scopedRules: List<Rule> = emptyList()

    private fun recomputeRuleScope() {
        scopedRules = ruleCache.filter { it.appliesToDevice(myDeviceId) }
    }

    @Volatile
    private var gateCache: List<Gate> = emptyList()

    private fun recomputeCalendarNeed() {
        // 凍結中は、使っているルールが残っていても読みに行かない
        if (!DopaFeatures.CALENDAR_ENABLED) {
            calendarNeeded = false
            return
        }
        // 2組目以降にカレンダーの条件が入っていることもある。1組目しか見ないと、
        // その条件だけ永久に成立しない(読み手を起こさないので)
        val usedByRule = ruleCache.any { rule ->
            rule.enabled && rule.clauses.any { usesCalendar(it.condition) }
        }
        val usedByGate = gateCache.any { it is Gate.CalendarWindow }
        calendarNeeded = usedByRule || usedByGate
        if (calendarNeeded) refreshCalendarIfStale(force = true)
    }

    /** そのルールが、凍結中の機能に頼っていて動かないか。編集画面で知らせるため。 */
    fun usesFrozenFeature(rule: Rule): Boolean =
        !DopaFeatures.CALENDAR_ENABLED && rule.clauses.any { usesCalendar(it.condition) }

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

    /**
     * いま前面に出ている画面の目印。サービスが判定の直前に入れる。
     *
     * ノードツリーを読むのは Android 側の仕事なので、ここは受け皿だけ持つ。
     * 前面が変わったら空に戻す ── 前の画面の目印を持ち越すと、別のアプリを
     * ショート扱いして塞ぐ事故になる。
     */
    @Volatile
    var currentScreenSignals: Set<String> = emptySet()

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
        minutesSinceBreakOf = { ruleId, breakMinutes -> minutesSinceBreak(ruleId, breakMinutes) },
        windowUsageOf = { ruleId, windowMinutes -> windowUsage(ruleId, windowMinutes) },
        minutesSinceLastUseOf = { ruleId -> minutesSinceLastUse(ruleId) },
        // 別の端末でそのルールが効いているか。**ここだけがネットに依存する。**
        // 届いていなければ偽 ── 圏外で塞がるより、圏外で緩むほうへ倒してある
        linkedActiveOf = { ruleUid, deviceId ->
            RuleStates.isActive(
                states = ruleStateCache,
                ruleUid = ruleUid,
                nowSec = System.currentTimeMillis() / 1000,
                myDeviceId = myDeviceId,
                deviceId = deviceId,
            )
        },
        withinReservation = reservations.covers(
            packageName,
            tagCache[packageName] ?: emptySet(),
            deviceId = myDeviceId,
        ),
        screenSignals = currentScreenSignals,
        overrideCountOf = { ruleId -> overrideCounts[ruleId] ?: 0 },
    )

    /**
     * そのルールの対象アプリをまとめて数えた、休憩をはさむまでの使用時間(分)。
     *
     * 対象の解決をここでやるのは、タグからアプリを引けるのが端末側だけだから。
     * 判定から同期的に呼ばれるので、キャッシュしてある一覧だけを見る。
     */
    private fun minutesSinceBreak(ruleId: Long, breakMinutes: Int): Int {
        val rule = ruleCache.firstOrNull { it.id == ruleId } ?: return 0
        return usage.minutesSinceBreak(breakMinutes) { pkg ->
            rule.target.matches(pkg, tagCache[pkg] ?: emptySet())
        }
    }

    /**
     * そのルールの対象について、いま張られている持ち時間の窓。
     *
     * 対象の解決は [minutesSinceBreak] と同じ理由でここに置く(タグを引けるのは端末側だけ)。
     */
    private fun windowUsage(ruleId: Long, windowMinutes: Int): WindowUsage {
        val rule = ruleCache.firstOrNull { it.id == ruleId } ?: return WindowUsage.NONE
        return usage.windowUsage(windowMinutes) { pkg ->
            rule.target.matches(pkg, tagCache[pkg] ?: emptySet())
        }
    }

    /** そのルールの対象を前回いつまで使っていたか(分前)。一度も無ければ null。 */
    private fun minutesSinceLastUse(ruleId: Long): Int? {
        val rule = ruleCache.firstOrNull { it.id == ruleId } ?: return null
        return usage.minutesSinceLastUse { pkg ->
            rule.target.matches(pkg, tagCache[pkg] ?: emptySet())
        }
    }

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
            rules = scopedRules,
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

    /**
     * 時間切れで閉め出す。破ったからではなく、取り決めどおりに閉まる。
     *
     * 罰([punish])と同じ道を通す ── 範囲の解決も、繰り返しで長くする計算も
     * [Consequence] が持っているので、閉め方を2通り持たずに済む。
     * 違うのは筋道だけで、ポイントは動かさない(違反ではないため)。
     *
     * 使用時間のセッションはここで閉じる。閉め出しているあいだも数え続けると、
     * 明けた瞬間にまた閾値を超えていて、二度と開かなくなる。
     *
     * @return 科した閉め出し。すぐ画面に出すために返す。
     */
    fun lockByRule(packageName: String, rule: Rule, params: Params): Lockout? {
        if (!initialized) return null
        val consequence = LockoutAction.consequenceOf(params)
        val target = consequence.resolveTarget(packageName, rule.target) ?: return null
        val repeats = if (consequence.lockEscalates) lockouts.recentCountFor(rule.name) else 0
        val notice = params.string(LockoutAction.KEY_NOTICE).ifBlank { rule.name }
        usage.onForegroundChanged(null)
        return lockouts.impose(
            target = target,
            minutes = consequence.lockMinutesFor(repeats),
            reason = notice,
        )
    }

    /** そのアプリにいま効いている封鎖。無ければ null。 */
    fun lockedNow(packageName: String): Lockout? {
        if (!initialized) return null
        val nowSec = System.currentTimeMillis() / 1000
        return Lockouts.activeFor(
            all = lockouts.current(nowSec),
            packageName = packageName,
            tagsOfApp = tagCache[packageName] ?: emptySet(),
            nowSec = nowSec,
        )
    }

    /** 閉め出しているあいだは使用時間を数えない。 */
    fun pauseUsageTracking() {
        if (initialized) usage.onForegroundChanged(null)
    }

    /**
     * 閉め出しが明けたので、また数え始める。
     *
     * 同じアプリで呼び直しても区間は増えない(伸びるだけ)。
     * ここが無いと、明けたあとの使用がどこにも残らず、次の閉め出しが来ない。
     */
    fun resumeUsageTracking(packageName: String) {
        if (initialized) usage.onForegroundChanged(packageName)
    }

    /** ブロック画面から引き返した。 */
    fun reward(rule: Rule) {
        if (!initialized || !pointPolicy.enabled) return
        val delta = pointPolicy.keepDelta(rule.consequence.keepPoints)
        if (delta != 0) points.record(delta, PointReason.BACKED_OFF, rule.name)
    }

    // ---- 自分で始める集中 ------------------------------------------------

    /** いま走っている集中。罰は含まない。 */
    fun activeFocus(): Lockout? = if (initialized) lockouts.activeFocus() else null

    /**
     * 集中を始める。5分刻みに丸められる。
     *
     * @return 始められたら true。すでに走っていれば false。
     */
    fun startFocus(minutes: Int = focusSettings.defaultMinutes, label: String = ""): Boolean {
        if (!initialized) return false
        lockouts.startFocus(
            minutes = minutes,
            allowPackages = focusSettings.allowPackages,
            allowTags = focusSettings.allowTags,
            effort = focusSettings.abortEffort,
            abortPoints = if (pointPolicy.enabled) pointPolicy.focusAbortCost else 0,
            label = label,
        ) ?: return false
        DopaAccessibilityService.kickEvaluation()
        refreshWidget()
        return true
    }

    /** 型を id で引く。ショートカットは id だけ持って呼んでくる。 */
    fun focusTemplate(id: String): com.dopachiru.core.model.FocusTemplate? =
        focusSettings.templates.firstOrNull { it.id == id }

    /**
     * 型を指定して集中を始める。範囲は型が決める(グループだけ・グループ以外・全部)。
     *
     * @return 始められたら true。すでに走っている / 型が壊れているなら false。
     */
    fun startFocus(template: com.dopachiru.core.model.FocusTemplate, minutes: Int): Boolean {
        if (!initialized || !template.isUsable) return false
        lockouts.startFocusWithTarget(
            target = template.target(),
            minutes = minutes,
            effort = focusSettings.abortEffort,
            abortPoints = if (pointPolicy.enabled) pointPolicy.focusAbortCost else 0,
            label = template.displayLabel(),
        ) ?: return false
        DopaAccessibilityService.kickEvaluation()
        refreshWidget()
        return true
    }

    /**
     * ホーム画面のウィジェットを描き直す。
     *
     * 集中が始まった・伸びた・明けた・切り上げられた瞬間に呼ぶ。呼ばないと、
     * 明けたあとも残り時間が残って見える。置かれていなければ何もしない。
     */
    private fun refreshWidget() {
        if (!::appContext.isInitialized) return
        runCatching { FocusWidget.refresh(appContext) }
    }

    /** 走っている集中に時間を足す。 */
    fun extendFocus(addMinutes: Int): Boolean {
        if (!initialized) return false
        lockouts.extendFocus(addMinutes) ?: return false
        DopaAccessibilityService.kickEvaluation()
        refreshWidget()
        return true
    }

    /**
     * 集中を時間より前に終わらせる。
     *
     * 猶予のうちは無料。それ以降はポイントを払う ── 払えなければ終われない。
     * 手間(長押しや打ち込み)は画面側で先に通してある。
     */
    fun endFocusEarly(): Boolean {
        if (!initialized) return false
        val now = System.currentTimeMillis() / 1000
        val focus = lockouts.activeFocus(now) ?: return false

        val cost = if (focus.canCancelFreelyAt(now)) 0 else (focus.earlyExit?.points ?: 0)
        if (cost > 0 && !canAfford(cost)) return false

        lockouts.endFocus() ?: return false
        if (cost > 0) {
            points.record(-cost, PointReason.FOCUS_ABORTED, "残り${focus.remainingMinutesAt(now)}分")
        }
        DopaAccessibilityService.kickEvaluation()
        refreshWidget()
        return true
    }

    /**
     * 走り切った集中に加点する。
     *
     * 期限が切れた瞬間を捉える場所が無いので、掃除のついでに見る。
     * 同じ集中で二度加点しないよう uid を鍵にする。
     */
    fun awardFinishedFocus(finished: List<Lockout>) {
        val policy = pointPolicy
        if (!policy.enabled || policy.focusDonePoints == 0) return
        finished.asSequence().filter { it.isChosen }.forEach { focus ->
            val minutes = ((focus.untilEpochSec - focus.createdAtEpochSec) / 60).toInt()
            points.record(
                delta = policy.focusDonePoints,
                reason = PointReason.FOCUS_DONE,
                note = "${minutes}分",
                dedupKey = "focus:${focus.uid}",
            )
        }
    }

    /** 押し切るのにいくら要るか。0 なら代金は取らない。 */
    fun overrideCost(rule: Rule): Int = pointPolicy.overrideCost(rule.consequence.breakPoints)

    /**
     * 端末間の同期。**制限の実行はこれに依存しません。**
     * 落ちていても圏外でも、判定はローカルのルールで続きます。
     */
    lateinit var sync: SyncManager
        private set

    /** いまの残高。画面から同期的に読む。 */
    fun pointBalance(): Int = if (initialized) points.currentBalance() else 0

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
                // どれか1組でも成立していれば「条件を満たしている」
                conditionMet = rule.clauses.any {
                    engine.evaluate(it.condition, ctx.forClause(rule, it))
                },
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
        val expired = lockouts.purgeExpired()
        awardFinishedFocus(expired)
        // 明けた瞬間にウィジェットを戻す。放っておくと残り時間が残って見える
        if (expired.any { it.isChosen }) refreshWidget()
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
        publishRuleStatesIfDue()
        syncIfDue()
        startScheduledFocusIfDue()
        return if (batterySaverMode) SAVER_TICK_MS else ACTIVE_TICK_MS
    }

    // ---- 自分で始めなくても始まる集中 ----------------------------------

    @Volatile
    private var focusSchedules: List<FocusSchedule> = emptyList()

    /**
     * 予定された集中を、時間が来ていれば始める。
     *
     * ## なぜ [tick] から呼ぶのか
     *
     * 集中を**自分の起動に依存させない**のがこの機能の中身なので、画面を開いた
     * ときではなく常駐の刻みから見ます。裏を返すと、**画面が消えているあいだは
     * 始まりません** ── [tick] 自体が止まるためです。眺めていないなら
     * 取り上げるものが無いので、それで構いません。
     *
     * ## 二度始めない
     *
     * 走らせた日を [SettingsStore.focusScheduleRuns] に書きます。これが無いと、
     * 明けた瞬間にまた条件を満たして始まり、永久に閉まります。
     * **書くのは実際に始められたときだけ**にしてあります ── 先に書いてしまうと、
     * すでに別の集中が走っていて始められなかった日が「やった日」として潰れます。
     */
    private fun startScheduledFocusIfDue() {
        val schedules = focusSchedules
        if (schedules.isEmpty()) return
        // すでに何か閉まっているなら足さない。集中の上に集中を重ねない
        if (lockouts.current().isNotEmpty()) return

        val now = now()
        val today = now.toLocalDate()
        val periodStartSec = ResetPolicy().periodStart(now)
            .atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        val firstTouchMinute = usage.firstUseSecSince(periodStartSec)?.let { sec ->
            val at = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(sec),
                java.time.ZoneId.systemDefault(),
            )
            at.hour * 60 + at.minute
        }

        val due = schedules.firstOrNull {
            FocusSchedules.isDue(it, now, firstTouchMinute, scheduleRuns[it.uid]?.let(LocalDate::parse))
        } ?: return

        val started = startFocus(due.minutes, label = due.stepFor(today).ifBlank { due.label + "の集中" })
        if (!started) return

        scope.launch {
            val next = scheduleRuns + (due.uid to today.toString())
            scheduleRuns = next
            settings.setFocusScheduleRuns(next)
        }
    }

    /** 予定ごとの「最後に走らせた日」。判定から同期的に読むのでキャッシュする。 */
    @Volatile
    private var scheduleRuns: Map<String, String> = emptyMap()

    // ---- 端末をまたいだ頼みごと ----------------------------------------

    @Volatile
    private var lastSyncAtMs: Long = 0L

    /**
     * 自動同期。常駐の刻みに相乗りします。
     *
     * これが無いと、端末をまたいだ頼みごとは**設定画面のボタンを押すまで届きません**。
     * WorkManager を足さないのは、常駐サービスがもう回っているから ── 依存を1つ
     * 増やすより、いまある刻みの裏に乗せるほうが壊れる場所が少ない。
     *
     * 画面が消えているあいだは [tick] 自体が呼ばれないので、ここも止まります。
     * スマホは主に**頼む側**なので、受け取りが遅れても困りません。
     */
    private fun syncIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastSyncAtMs < SYNC_EVERY_MS) return
        lastSyncAtMs = now
        scope.launch {
            // 同期と同じ刻みに乗せる。その場の枠の期限は分単位なので、
            // 専用の刻みを足すほどのことではない
            runCatching { rules.purgeExpired() }

            val settings = this@DopaRuntime.settings.syncSettings.first()
            if (!settings.enabled || !settings.isConfigured) return@launch
            runCatching { sync.syncNow() }
            runInbox()
        }
    }

    @Volatile
    private var lastPublishAtMs = 0L

    /**
     * 連動の状態を見直す。**変わっていたら、次の同期を待たずに送る。**
     *
     * 同期の刻み(5分)に任せると、向こうが塞がるまで最大で5分+相手の刻みぶん
     * かかります。閉まるべき瞬間に閉まらないのはこの機能の値打ちを削るので、
     * 変わったときだけ刻みを飛ばします。見直し自体は1分おき ── 対象は
     * 「見られているルール」だけなので軽い。
     */
    private fun publishRuleStatesIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPublishAtMs < PUBLISH_EVERY_MS) return
        lastPublishAtMs = now
        scope.launch {
            val changed = runCatching { publishRuleStates() }.getOrDefault(false)
            // 変わったのに5分待たせない。次の syncIfDue がその場で通る
            if (changed) lastSyncAtMs = 0L
        }
    }

    /**
     * この端末でどのルールが効いているかを書き出す。次の同期で配られる。
     *
     * ## 自分の手柄だけを書く
     *
     * 評価するときは**連動そのものを外します**([EvalContext.linkedActiveOf] を偽に
     * 固定)。外さないと、A が「B が効いているから効いている」と書き、B が
     * 「A が効いているから効いている」と書いて、どちらも永久に外れません。
     *
     * ## 見られているルールだけ書く
     *
     * どこからも指されていないルールの状態を配っても誰も読まない。
     * 毎回全部書くと、何も起きていない日でも同期のたびに行が動きます。
     */
    private suspend fun publishRuleStates(): Boolean {
        val deviceId = myDeviceId
        if (deviceId.isBlank()) return false

        val all = ruleCache
        val watched = RuleLinks.watchedUids(all)
        if (watched.isEmpty()) return false

        val now = System.currentTimeMillis() / 1000
        val nowTime = LocalDateTime.now()
        // 連動を外した文脈。ここで外さないと、向こうの状態が自分に跳ね返る
        val base = buildContext("", nowTime).copy(linkedActiveOf = { _, _ -> false })

        var states = settings.ruleStates.first()
        var changed = false

        for (rule in all) {
            if (rule.uid !in watched) continue
            if (!rule.appliesToDevice(deviceId)) continue

            // どれか1組でも縛っていれば「効いている」。連動は組の単位ではなく
            // ルールの単位で見る ── 向こうの端末は組の番号を知らない
            val active = rule.enabled && rule.clauses.any {
                engine.evaluate(it.condition, base.forClause(rule, it))
            }
            val previous = states.firstOrNull { it.ruleUid == rule.uid && it.deviceId == deviceId }
            if (!RuleStates.shouldPublish(previous, active, now)) continue

            val next = RuleStates.publish(rule.uid, deviceId, active, now)
            states = states.filterNot { it.uid == next.uid } + next
            sync.touch(SyncKinds.RULE_STATES, next.uid)
            changed = true
        }

        if (changed) settings.setRuleStates(RuleStates.prune(states, now))
        return changed
    }

    /**
     * 短い合言葉と引き換えに、本物の合言葉を受け取る。
     *
     * 48文字の合言葉を PC から写すのが面倒、というだけのための道です。
     * カメラも権限も要らない代わりに、サーバー側で**2分・使い切り**に絞ってあります。
     */
    suspend fun claimInvite(baseUrl: String, code: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) return@withContext Result.failure(
                IllegalArgumentException("先にサーバーの住所を入れてください"),
            )
            // 合言葉をまだ持っていないので、空のまま呼ぶ(この口だけ認証が要らない)
            when (val out = SyncApi(baseUrl, "").claimInvite(code)) {
                is SyncApi.Outcome.Ok ->
                    out.value.token.takeIf { it.isNotBlank() }
                        ?.let { Result.success(it) }
                        ?: Result.failure(IllegalStateException("サーバーが合言葉を持っていません"))

                is SyncApi.Outcome.Rejected ->
                    // 見つからないのと切れたのをサーバーは区別しない(総当たりの手掛かりになる)
                    Result.failure(
                        IllegalStateException(
                            if (out.code == 404) "その合言葉は見つかりません。切れているかもしれません" else out.message,
                        ),
                    )

                is SyncApi.Outcome.Unreachable -> Result.failure(IllegalStateException(out.message))
                is SyncApi.Outcome.Malformed -> Result.failure(IllegalStateException(out.message))
            }
        }

    /** 名簿。画面で相手を選ぶために使う。 */
    val devices: Flow<List<DeviceInfo>> get() = settings.devices

    /** 出したもの・受け取ったものの一覧。 */
    val commands: Flow<List<Command>> get() = settings.commands

    /**
     * 別の端末に頼む。**積むだけで、実行はしません。**
     *
     * 積んだ直後に同期を回します ── 押してから刻み1回ぶん待たされると、
     * 「効かない」と思ってもう一度押すことになります。
     */
    suspend fun requestOnDevice(
        to: String,
        kind: String,
        params: Params = Params.EMPTY,
        reason: String = "",
    ): Command {
        val nowSec = System.currentTimeMillis() / 1000
        val command = Command(
            uid = UUID.randomUUID().toString(),
            to = to,
            from = myDeviceId,
            kind = kind,
            params = params,
            reason = reason,
            issuedAtSec = nowSec,
            expiresAtSec = nowSec + if (CommandKind.loosens(kind)) {
                Commands.LOOSEN_TTL_SEC
            } else {
                Commands.TIGHTEN_TTL_SEC
            },
        )
        putCommand(command)
        scope.launch { runCatching { sync.syncNow() } }
        return command
    }

    /** 出した頼みを取り下げる。相手がまだ実行していなければ効く。 */
    suspend fun cancelCommand(uid: String) {
        val command = settings.commands.first().firstOrNull { it.uid == uid } ?: return
        if (!command.isOpen) return
        putCommand(
            command.copy(
                state = CommandState.CANCELLED,
                handledAtSec = System.currentTimeMillis() / 1000,
            ),
        )
        scope.launch { runCatching { sync.syncNow() } }
    }

    /**
     * 届いた頼みごとを捌く。
     *
     * 通すかどうかの判断は core の [Commands.triage] に1つだけ置いてあります ──
     * Windows と別々に書くと、かたや関門を通しかたや素通し、が必ず起きます。
     * ここがやるのは、通ったものを **Android のやり方で実行すること**だけ。
     */
    suspend fun runInbox() {
        val me = myDeviceId
        if (me.isBlank()) return
        val nowSec = System.currentTimeMillis() / 1000
        var changed = false

        for (command in settings.commands.first()) {
            when (val verdict = Commands.triage(command, me, gateCache, nowSec)) {
                is CommandVerdict.Ignore -> Unit

                is CommandVerdict.Drop -> {
                    putCommand(
                        command.copy(state = verdict.state, note = verdict.note, handledAtSec = nowSec),
                    )
                    changed = true
                }

                is CommandVerdict.Wait -> {
                    val note = "あと: " + verdict.describe()
                    // 受け取った時刻はクールダウンの起点なので、一度書いたら上書きしない
                    if (command.state == CommandState.PENDING) {
                        putCommand(
                            command.copy(
                                state = CommandState.ACCEPTED,
                                acceptedAtSec = nowSec,
                                note = note,
                            ),
                        )
                        changed = true
                    } else if (command.note != note) {
                        putCommand(command.copy(note = note))
                        changed = true
                    }
                }

                is CommandVerdict.Run -> {
                    val note = execute(command)
                    putCommand(
                        command.copy(
                            state = if (note.startsWith("×")) CommandState.REFUSED else CommandState.DONE,
                            note = note,
                            handledAtSec = nowSec,
                        ),
                    )
                    changed = true
                }
            }
        }

        // 答えを返す。返さないと、送った側はいつまでも「届けています」のまま
        if (changed) runCatching { sync.syncNow() }
    }

    /** 実行そのもの。Android のやり方。頭に × を付けると断ったことになる。 */
    private suspend fun execute(command: Command): String = when (command.kind) {
        CommandKind.FOCUS_START, CommandKind.LOCK_NOW -> {
            val minutes = command.params.int(CommandKind.KEY_MINUTES, 25)
            if (startFocus(minutes)) "${minutes}分の集中を始めました" else "×すでに集中しています"
        }

        CommandKind.RULE_ENABLE, CommandKind.RULE_DISABLE -> {
            val uid = command.params.string(CommandKind.KEY_RULE_UID)
            val rule = rules.getAll().firstOrNull { it.uid == uid }
            if (rule == null) {
                "×そのルールがこの端末にありません"
            } else {
                val on = command.kind == CommandKind.RULE_ENABLE
                rules.setEnabled(rule.id, on)
                "「${rule.name}」を" + (if (on) "有効にしました" else "止めました")
            }
        }

        CommandKind.UNLOCK -> {
            val count = lockouts.clearAll()
            if (count == 0) "閉まっているものはありませんでした" else "${count}件を開けました"
        }

        CommandKind.PASS -> {
            val minutes = command.params.int(CommandKind.KEY_MINUTES, 15)
            settings.setPassUntil(System.currentTimeMillis() / 1000 + minutes * 60L)
            "${minutes}分の解禁券を使いました"
        }

        else -> "×知らない頼みです(${command.kind})"
    }

    private suspend fun putCommand(command: Command) {
        val list = settings.commands.first()
        settings.setCommands(list.filterNot { it.uid == command.uid } + command)
        sync.touch(SyncKinds.COMMANDS, command.uid)
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
    /**
     * 自動同期の間隔。
     *
     * Windows(1分)より長いのは、スマホが主に**頼む側**だから ── 受け取りが
     * 数分遅れても困らず、通信と電池のほうが惜しい。頼んだ瞬間には別途すぐ送ります。
     */
    /**
     * 連動の状態を見直す間隔。
     *
     * 同期(5分)より短い。変わったときだけ同期を前倒しするので、
     * 見直しが遅いとそのぶん閉まるのが遅れる。見るのは
     * 「ほかから見られているルール」だけなので、軽い。
     */
    private const val PUBLISH_EVERY_MS = 60_000L

    private const val SYNC_EVERY_MS = 5L * 60 * 1000

    private const val SAVER_TICK_MS = 180_000L
    private const val IDLE_TICK_MS = 600_000L
    private const val CALENDAR_ACTIVE_MS = 5 * 60_000L
    private const val CALENDAR_SAVER_MS = 15 * 60_000L
}
