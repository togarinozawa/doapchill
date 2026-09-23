package com.dopachiru.desktop.data

import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.ReservationPolicy
import com.dopachiru.core.model.Rule
import com.dopachiru.core.sync.AppInfo
import com.dopachiru.core.sync.RuleCatalogs
import com.dopachiru.core.sync.DeviceInfo
import com.dopachiru.core.sync.Envelope
import com.dopachiru.core.sync.MergeAction
import com.dopachiru.core.sync.RuleStates
import com.dopachiru.core.sync.SyncApi
import com.dopachiru.core.sync.SyncKinds
import com.dopachiru.core.sync.SyncMapper
import com.dopachiru.core.sync.SyncRequest
import com.dopachiru.core.sync.SyncSettings
import com.dopachiru.core.sync.UsageDay
import com.dopachiru.core.sync.UsageUpload
import com.dopachiru.core.sync.decideMerge
import com.dopachiru.desktop.platform.ForegroundApp
import java.time.LocalDate

/**
 * Windows 側の同期。
 *
 * Android の `SyncManager` と**同じ手順**を、Room ではなく JSON の店に対してやります。
 * 判定も変換も core にあるので、ここに残っているのは
 * 「どこから読んでどこへ書くか」だけです。
 *
 * **制限の実行はこれに依存しません。** 落ちていても縛りは効いたままで、
 * 止まって起きるのは「別の端末の予約や連動がまだ届かない」だけです。
 */
object DesktopSync {

    sealed interface Outcome {
        data class Done(val pulled: Int, val pushed: Int) : Outcome
        data object NotConfigured : Outcome
        data class Failed(val message: String) : Outcome
    }

    const val PLATFORM = AppInfo.WINDOWS

    private const val USAGE_DAYS = 14
    private const val TOMBSTONE_KEEP_SEC = 90L * 24 * 60 * 60

    /** 終わった頼みごとを残しておく長さ。送った側の画面に「済み」を出すため。 */
    private const val COMMAND_KEEP_SEC = 3L * 24 * 60 * 60

    /** 自分の名札の指紋を覚えておく置き場。受け取った名札の時刻とは分ける。 */
    private const val CATALOG_STAMP = SyncKinds.RULE_CATALOGS + "#self"

    /**
     * 1往復。**呼ぶ側が別スレッドに逃がしてください** ── 素直に待ちます。
     *
     * @param reservations 予約は別の店に住んでいるので、出し入れを引数で受けます。
     * @param selfName この端末の名前。名簿に載せる。
     * @param selfVersion アプリの版。食い違いの切り分け用。
     * @param policyOf 予約で開くルールの型。名札に載せて、スマホから取るときに使わせる。
     */
    fun run(
        file: RuleFile,
        reservations: List<Reservation>,
        settings: SyncSettings,
        usage: List<UsageDay>,
        selfName: String,
        selfVersion: String,
        policyOf: (Rule) -> ReservationPolicy,
        onApply: (RuleFile) -> Unit,
        onReservations: (List<Reservation>) -> Unit,
        onSettings: (SyncSettings) -> Unit,
    ): Outcome {
        if (!settings.enabled || !settings.isConfigured) return Outcome.NotConfigured

        // 名札は中身が変わったときだけ時刻を進める。毎回いまの時刻で送ると、
        // 何も変えていない日でも同期のたびにほかの端末が受け取り直す
        val catalog = RuleCatalogs.of(settings.deviceId, file.rules, policyOf)
        val catalogKey = RuleCatalogs.contentKey(catalog)
        val catalogAt = file.stampOf(CATALOG_STAMP, catalogKey)?.updatedAt ?: nowSec()

        val api = SyncApi(settings.baseUrl, settings.token)
        val outgoing = collect(file, reservations, settings, selfName, selfVersion) +
            (SyncKinds.RULE_CATALOGS to listOf(SyncMapper.catalogEnvelope(catalog, catalogAt)))

        val response = when (
            val r = api.sync(
                SyncRequest(deviceId = settings.deviceId, since = settings.since, changes = outgoing),
            )
        ) {
            is SyncApi.Outcome.Ok -> r.value
            is SyncApi.Outcome.Unreachable -> return failed(settings, r.message, onSettings)
            is SyncApi.Outcome.Rejected -> return failed(settings, r.message, onSettings)
            is SyncApi.Outcome.Malformed -> return failed(settings, r.message, onSettings)
        }

        var next = file.copy(
            syncState = file.syncState.filterKeys { !it.startsWith("$CATALOG_STAMP|") } +
                ("$CATALOG_STAMP|$catalogKey" to SyncStamp(catalogAt)),
        )
        var nextReservations = reservations
        var pulled = 0

        // ---- ルールの名札 ---------------------------------------------
        // 1台ぶんをまるごと置き換える。自分のぶんは入れない(手元のルールが正)
        for (envelope in response.of(SyncKinds.RULE_CATALOGS)) {
            val localAt = next.stampOf(SyncKinds.RULE_CATALOGS, envelope.uid)?.updatedAt
            if (decideMerge(envelope, localAt) != MergeAction.Apply) continue
            val received = SyncMapper.catalogOf(envelope) ?: continue
            if (received.deviceId == settings.deviceId) continue
            next = next.copy(
                ruleCatalogs = next.ruleCatalogs.filterNot { it.deviceId == received.deviceId } + received,
            ).withStamp(SyncKinds.RULE_CATALOGS, envelope.uid, SyncStamp(envelope.updatedAt, false))
            pulled++
        }

        for (envelope in response.of(SyncKinds.TAGS)) {
            if (decideMerge(envelope, next.stampOf(SyncKinds.TAGS, envelope.uid)?.updatedAt) !=
                MergeAction.Apply
            ) {
                continue
            }
            val (process, tags) = SyncMapper.tagsOf(envelope, PLATFORM) ?: continue
            next = next.copy(tags = next.tags + (process to tags))
                .withStamp(SyncKinds.TAGS, envelope.uid, SyncStamp(envelope.updatedAt, false))
            pulled++
        }

        for (envelope in response.of(SyncKinds.APPS)) {
            if (decideMerge(envelope, next.stampOf(SyncKinds.APPS, envelope.uid)?.updatedAt) !=
                MergeAction.Apply
            ) {
                continue
            }
            val info = SyncMapper.appOf(envelope) ?: continue
            ForeignAppLabels.remember(info)
            next = next.withStamp(SyncKinds.APPS, envelope.uid, SyncStamp(envelope.updatedAt, false))
            pulled++
        }

        // ---- 端末の名簿 ----------------------------------------------
        // 名簿は古いほうを残す理由が無いので、届いたものをそのまま置き換える
        for (envelope in response.of(SyncKinds.DEVICES)) {
            val info = SyncMapper.deviceOf(envelope) ?: continue
            next = next.copy(
                devices = next.devices.filterNot { it.deviceId == info.deviceId } + info,
            ).withStamp(SyncKinds.DEVICES, envelope.uid, SyncStamp(envelope.updatedAt, false))
            pulled++
        }

        // ---- 予約 ------------------------------------------------------
        for (envelope in response.of(SyncKinds.RESERVATIONS)) {
            val localAt = next.stampOf(SyncKinds.RESERVATIONS, envelope.uid)?.updatedAt
            when (decideMerge(envelope, localAt)) {
                MergeAction.Skip -> Unit

                MergeAction.Delete -> {
                    nextReservations = nextReservations.filterNot { it.uid == envelope.uid }
                    next = next.withStamp(
                        SyncKinds.RESERVATIONS,
                        envelope.uid,
                        SyncStamp(envelope.updatedAt, true),
                    )
                    pulled++
                }

                MergeAction.Apply -> {
                    val reservation = SyncMapper.reservationOf(envelope) ?: continue
                    val existing = nextReservations.firstOrNull { it.uid == envelope.uid }
                    val placed = reservation.copy(id = existing?.id ?: 0L)
                    nextReservations = if (existing == null) {
                        nextReservations + placed
                    } else {
                        nextReservations.map { if (it.uid == envelope.uid) placed else it }
                    }
                    next = next.withStamp(
                        SyncKinds.RESERVATIONS,
                        envelope.uid,
                        SyncStamp(envelope.updatedAt, false),
                    )
                    pulled++
                }
            }
        }

        // ---- 頼みごと --------------------------------------------------
        // 実行はここではしない。届いたものを置くだけで、通すかどうかは
        // DesktopRuntime が自分の関門で決める
        for (envelope in response.of(SyncKinds.COMMANDS)) {
            val localAt = next.stampOf(SyncKinds.COMMANDS, envelope.uid)?.updatedAt
            when (decideMerge(envelope, localAt)) {
                MergeAction.Skip -> Unit

                MergeAction.Delete -> {
                    next = next.copy(commands = next.commands.filterNot { it.uid == envelope.uid })
                        .withStamp(SyncKinds.COMMANDS, envelope.uid, SyncStamp(envelope.updatedAt, true))
                    pulled++
                }

                MergeAction.Apply -> {
                    val command = SyncMapper.commandOf(envelope) ?: continue
                    next = next.copy(
                        commands = next.commands.filterNot { it.uid == envelope.uid } + command,
                    ).withStamp(SyncKinds.COMMANDS, envelope.uid, SyncStamp(envelope.updatedAt, false))
                    pulled++
                }
            }
        }

        // ---- ルールが効いているか ---------------------------------------
        // **自分のぶんは入れない。** 同じルールが端末をまたいで同じ uid を持つので、
        // 自分の状態を読み戻すと、一度効いたら永久に外れなくなる
        for (envelope in response.of(SyncKinds.RULE_STATES)) {
            val localAt = next.stampOf(SyncKinds.RULE_STATES, envelope.uid)?.updatedAt
            if (decideMerge(envelope, localAt) != MergeAction.Apply) continue
            val state = SyncMapper.ruleStateOf(envelope) ?: continue
            if (state.deviceId == settings.deviceId) continue
            next = next.copy(
                ruleStates = next.ruleStates.filterNot { it.uid == state.uid } + state,
            ).withStamp(SyncKinds.RULE_STATES, envelope.uid, SyncStamp(envelope.updatedAt, false))
            pulled++
        }
        next = next.copy(ruleStates = RuleStates.prune(next.ruleStates, nowSec()))

        // 古い墓標を落とす。長く寝ていた端末が復活させない程度には残す
        val cutoff = nowSec() - TOMBSTONE_KEEP_SEC
        next = next.copy(
            syncState = next.syncState.filterNot { (_, s) -> s.deleted && s.updatedAt < cutoff },
            // 終わった頼みごとは少しだけ残す(送った側に「済み」を出すため)
            commands = next.commands.filterNot {
                !it.isOpen && it.handledAtSec in 1 until (nowSec() - COMMAND_KEEP_SEC)
            },
        )
        onApply(next)
        onReservations(nextReservations)

        // 実績は別の口。落ちても同期そのものは成立したことにする ──
        // ルールが配れているのに「失敗」と出ると、直す先を見誤る
        val usageError = when (
            val u = api.uploadUsage(UsageUpload(settings.deviceId, usage.take(USAGE_DAYS)))
        ) {
            is SyncApi.Outcome.Ok -> ""
            is SyncApi.Outcome.Unreachable -> u.message
            is SyncApi.Outcome.Rejected -> u.message
            is SyncApi.Outcome.Malformed -> u.message
        }

        onSettings(
            settings.copy(
                since = response.rev,
                lastSyncedAtSec = nowSec(),
                lastError = if (usageError.isBlank()) "" else "実績だけ送れませんでした: $usageError",
            ),
        )
        return Outcome.Done(pulled = pulled, pushed = outgoing.values.sumOf { it.size })
    }

    /**
     * 送るものを集める。**毎回まるごと**送ります。
     *
     * 「前回から変わったぶんだけ」を端末側でやると、取りこぼしたときに
     * 二度と送られないバグが出ます。古いものはサーバー側が捨てます。
     */
    private fun collect(
        file: RuleFile,
        reservations: List<Reservation>,
        settings: SyncSettings,
        selfName: String,
        selfVersion: String,
    ): Map<String, List<Envelope>> {
        val tags = file.tags.map { (process, set) ->
            SyncMapper.tagsEnvelope(
                PLATFORM,
                process,
                set,
                file.stampOf(SyncKinds.TAGS, "$PLATFORM:$process")?.updatedAt ?: nowSec(),
            )
        }

        // 名札は、手元のルールとタグが触れているものだけ。
        // 動いているプロセスを全部送るのは、要らないうえに知られすぎる
        val referenced = buildSet {
            addAll(file.tags.keys)
            file.rules.forEach {
                addAll(it.target.packages)
                addAll(it.target.exceptPackages)
            }
        }
        val apps = referenced.map { process ->
            val info = AppInfo(process, ForegroundApp.labelFor(process), PLATFORM)
            SyncMapper.appEnvelope(
                info,
                file.stampOf(SyncKinds.APPS, info.uid)?.updatedAt ?: nowSec(),
            )
        }

        // この端末の行。毎回書くので、lastSeen がそのまま「最後に同期した時刻」になる
        val self = SyncMapper.deviceEnvelope(
            DeviceInfo(
                deviceId = settings.deviceId,
                name = selfName.ifBlank { settings.deviceId },
                platform = PLATFORM,
                version = selfVersion,
                lastSeenSec = nowSec(),
            ),
            updatedAt = nowSec(),
        )

        val liveReservations = HashSet<String>()
        val reservationEnvelopes = ArrayList<Envelope>()
        for (reservation in reservations) {
            if (reservation.uid.isBlank()) continue
            liveReservations += reservation.uid
            reservationEnvelopes += SyncMapper.reservationEnvelope(
                reservation,
                file.stampOf(SyncKinds.RESERVATIONS, reservation.uid)?.updatedAt ?: nowSec(),
            )
        }
        reservationEnvelopes += tombstones(file, SyncKinds.RESERVATIONS, liveReservations)

        val commands = file.commands.map { command ->
            SyncMapper.commandEnvelope(
                command,
                file.stampOf(SyncKinds.COMMANDS, command.uid)?.updatedAt ?: nowSec(),
            )
        }

        // ルールが効いているか。**この端末のぶんだけ送る** ──
        // 受け取ったぶんまで送り返すと、消えた端末の状態が生き続ける
        val ruleStates = file.ruleStates
            .filter { it.deviceId == settings.deviceId }
            .map { state ->
                SyncMapper.ruleStateEnvelope(
                    state,
                    file.stampOf(SyncKinds.RULE_STATES, state.uid)?.updatedAt ?: nowSec(),
                )
            }

        return mapOf(
            SyncKinds.TAGS to tags,
            SyncKinds.APPS to apps,
            SyncKinds.DEVICES to listOf(self),
            SyncKinds.RESERVATIONS to reservationEnvelopes,
            SyncKinds.COMMANDS to commands,
            SyncKinds.RULE_STATES to ruleStates,
        )
    }

    /** 消したものの墓標。無いと、消した端末以外から送り返されて生き返る。 */
    private fun tombstones(file: RuleFile, kind: String, live: Set<String>): List<Envelope> {
        val prefix = "$kind|"
        return file.syncState.mapNotNull { (key, stamp) ->
            if (!stamp.deleted || !key.startsWith(prefix)) return@mapNotNull null
            val uid = key.removePrefix(prefix)
            if (uid in live) return@mapNotNull null
            Envelope(uid = uid, updatedAt = stamp.updatedAt, deleted = true)
        }
    }

    private fun failed(
        settings: SyncSettings,
        message: String,
        onSettings: (SyncSettings) -> Unit,
    ): Outcome {
        onSettings(settings.copy(lastError = message))
        return Outcome.Failed(message)
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    /** 今日から遡って日付を並べる。実績を組み立てるときに使う。 */
    fun recentDates(days: Int = USAGE_DAYS): List<LocalDate> =
        (0 until days).map { LocalDate.now().minusDays(it.toLong()) }
}

/**
 * 他の端末から届いたアプリの名札。
 *
 * 入っていないアプリの名前は、その端末からしか分かりません。
 * 見た目にしか使わないので、素朴にメモリだけで持ちます
 * (消えても次の同期でまた届きます)。
 */
object ForeignAppLabels {
    private val labels = HashMap<String, String>()

    fun remember(info: AppInfo) {
        labels[info.uid] = info.label
    }

    /** 見つからなければ識別子をそのまま返します。空にするより読めるので。 */
    fun labelOf(uid: String): String = labels[uid] ?: AppInfo.idOf(uid)?.second ?: uid

    fun all(): Map<String, String> = labels.toMap()
}
