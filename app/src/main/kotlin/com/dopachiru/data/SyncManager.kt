package com.dopachiru.data

import android.content.Context
import com.dopachiru.core.sync.AppInfo
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
import com.dopachiru.data.db.SyncStateDao
import com.dopachiru.data.db.SyncStateEntity
import com.dopachiru.ui.rules.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 端末間の同期。
 *
 * ## 配るだけで、取り締まりには関わりません
 *
 * 判定はローカルのルールでやります。ここが動かなくても、圏外でも、機内モードでも
 * 縛りは効いたまま。**止まって起きるのは「別の端末で足したルールがまだ届かない」だけ**です。
 *
 * ## 毎回ぜんぶ送ります
 *
 * 「前回から変わったぶんだけ送る」を端末側でやると、**取りこぼしたときに
 * 二度と送られない**種類のバグが出ます。数個のルールなら数キロバイトなので、
 * 毎回まるごと送って、古いものはサーバー側の
 * `ON CONFLICT ... WHERE excluded.updated_at > ...` に捨てさせます。
 *
 * 受け取る側は `rev` のカーソルで差分だけ貰います。**番号を振るのはサーバー**なので、
 * 端末の時計がずれていても取りこぼしません。
 */
class SyncManager(
    private val context: Context,
    private val rules: RuleRepository,
    private val stats: StatsRepository,
    private val syncStateDao: SyncStateDao,
    private val settingsStore: SettingsStore,
) {
    sealed interface Outcome {
        data class Done(val pulled: Int, val pushed: Int) : Outcome
        data object NotConfigured : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** 1往復。画面から呼んでも安全なように、中で IO に逃がします。 */
    suspend fun syncNow(): Outcome = withContext(Dispatchers.IO) {
        val settings = settingsStore.syncSettings.first()
        if (!settings.enabled || !settings.isConfigured) return@withContext Outcome.NotConfigured

        val api = SyncApi(settings.baseUrl, settings.token)
        val outgoing = collect(settings)

        val response = when (
            val r = api.sync(
                SyncRequest(deviceId = settings.deviceId, since = settings.since, changes = outgoing),
            )
        ) {
            is SyncApi.Outcome.Ok -> r.value
            is SyncApi.Outcome.Unreachable -> return@withContext fail(settings, r.message)
            is SyncApi.Outcome.Rejected -> return@withContext fail(settings, r.message)
            is SyncApi.Outcome.Malformed -> return@withContext fail(settings, r.message)
        }

        val pulled = apply(
            settings,
            response.of(SyncKinds.TAGS),
            response.of(SyncKinds.APPS),
            response.of(SyncKinds.DEVICES),
            response.of(SyncKinds.RESERVATIONS),
            response.of(SyncKinds.COMMANDS),
            response.of(SyncKinds.RULE_STATES),
        )

        // 実績は別の口。落ちても同期そのものは成立したことにする ──
        // ルールが配れているのに「失敗」と出ると、直す先を見誤る
        val usageError = when (
            val u = api.uploadUsage(UsageUpload(settings.deviceId, recentUsage()))
        ) {
            is SyncApi.Outcome.Ok -> ""
            is SyncApi.Outcome.Unreachable -> u.message
            is SyncApi.Outcome.Rejected -> u.message
            is SyncApi.Outcome.Malformed -> u.message
        }

        settingsStore.setSyncSettings(
            settings.copy(
                since = response.rev,
                lastSyncedAtSec = System.currentTimeMillis() / 1000,
                lastError = if (usageError.isBlank()) "" else "実績だけ送れませんでした: $usageError",
            ),
        )

        // 墓標は十分に長く置いてから落とす。短いと、長く寝ていた端末が復活させる
        syncStateDao.purgeTombstones(System.currentTimeMillis() / 1000 - TOMBSTONE_KEEP_SEC)

        Outcome.Done(pulled = pulled, pushed = outgoing.values.sumOf { it.size })
    }

    private suspend fun fail(settings: SyncSettings, message: String): Outcome {
        settingsStore.setSyncSettings(settings.copy(lastError = message))
        return Outcome.Failed(message)
    }

    // ---- 送るものを集める --------------------------------------------------

    private suspend fun collect(settings: SyncSettings): Map<String, List<Envelope>> {
        val local = rules.getAll()
        val tagsByPackage = rules.currentTagsByPackage()
        val tagEnvelopes = tagsByPackage.map { (pkg, tags) ->
            SyncMapper.tagsEnvelope(PLATFORM, pkg, tags, stampFor(SyncKinds.TAGS, "$PLATFORM:$pkg"))
        }

        // 名札は、手元のルールとタグが触れているアプリぶんだけ。
        // 端末に入っている全アプリを送るのは、要らないうえに知られすぎる
        val referenced = buildSet {
            addAll(tagsByPackage.keys)
            local.forEach { rule ->
                addAll(rule.target.packages)
                addAll(rule.target.exceptPackages)
            }
        }
        val appEnvelopes = referenced.map { pkg ->
            val info = AppInfo(pkg, InstalledApps.labelOf(context, pkg), PLATFORM)
            SyncMapper.appEnvelope(info, stampFor(SyncKinds.APPS, info.uid))
        }

        // この端末の行。毎回書くので、lastSeen がそのまま「最後に同期した時刻」になる
        val now = System.currentTimeMillis() / 1000
        val self = SyncMapper.deviceEnvelope(
            DeviceInfo(
                deviceId = settings.deviceId,
                name = settingsStore.deviceName.first().ifBlank { settings.deviceId },
                platform = PLATFORM,
                version = appVersion(),
                lastSeenSec = now,
            ),
            updatedAt = now,
        )

        // 予約。**端末をまたいで配ります** ── 決める端末と使う端末が別でよい
        val liveReservations = HashSet<String>()
        val reservationEnvelopes = ArrayList<Envelope>()
        for (reservation in settingsStore.reservations.first()) {
            if (reservation.uid.isBlank()) continue
            liveReservations += reservation.uid
            reservationEnvelopes += SyncMapper.reservationEnvelope(
                reservation,
                stampFor(SyncKinds.RESERVATIONS, reservation.uid),
            )
        }
        for (state in syncStateDao.ofKind(SyncKinds.RESERVATIONS)) {
            if (!state.deleted || state.uid in liveReservations) continue
            reservationEnvelopes += Envelope(state.uid, state.updatedAt, deleted = true)
        }

        // 頼みごと。**配るのは頼みであって実行ではありません**
        val commandEnvelopes = settingsStore.commands.first().map { command ->
            SyncMapper.commandEnvelope(command, stampFor(SyncKinds.COMMANDS, command.uid))
        }

        // ルールが効いているか。**この端末のぶんだけ送ります** ──
        // 受け取ったぶんまで送り返すと、消えた端末の状態が生き続ける
        val stateEnvelopes = settingsStore.ruleStates.first()
            .filter { it.deviceId == settings.deviceId }
            .map { SyncMapper.ruleStateEnvelope(it, stampFor(SyncKinds.RULE_STATES, it.uid)) }

        return mapOf(
            SyncKinds.TAGS to tagEnvelopes,
            SyncKinds.APPS to appEnvelopes,
            SyncKinds.DEVICES to listOf(self),
            SyncKinds.RESERVATIONS to reservationEnvelopes,
            SyncKinds.COMMANDS to commandEnvelopes,
            SyncKinds.RULE_STATES to stateEnvelopes,
        )
    }

    /**
     * タグと名札の「いつ変えたか」。
     *
     * どちらも行に時刻を持っていないので、ここで覚えます。
     * **初めて見たときだけ現在時刻を書き、以降は据え置き**にするのが肝で、
     * 毎回いまの時刻を送ると、2台が同じものを永遠に押し付け合います。
     *
     * 裏を返すと、**あとから名前が変わっても送り直しません**(アプリの改名など)。
     * 名札は見た目だけのものなので、往復を止めるほうを優先しています。
     */
    private suspend fun stampFor(kind: String, uid: String): Long {
        syncStateDao.get(kind, uid)?.let { return it.updatedAt }
        val now = System.currentTimeMillis() / 1000
        syncStateDao.put(SyncStateEntity(kind, uid, now, deleted = false))
        return now
    }

    /**
     * この版の番号。名簿に載せて、端末どうしの食い違いの切り分けに使う。
     *
     * `BuildConfig` を生やさずに PackageManager から読むのは、この1文字列のために
     * ビルドの設定を増やしたくないため。読めなければ空でよい(見た目だけのもの)。
     */
    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * 「いま変えた」と記録する。
     *
     * 予約と頼みごとは Room の表ではなく DataStore の JSON に住んでいるので、
     * 行に更新時刻を持てません。**変えた側がここを叩かないと、据え置きの時刻のまま
     * 送られてサーバーに弾かれます**(後に書かれたほうが勝つので、同じ時刻は負ける)。
     */
    suspend fun touch(kind: String, uid: String) {
        if (uid.isBlank()) return
        syncStateDao.put(
            SyncStateEntity(kind, uid, System.currentTimeMillis() / 1000, deleted = false),
        )
    }

    /** 消したことを覚える。残さないと、次の同期で別の端末から送り返されて生き返ります。 */
    suspend fun tombstone(kind: String, uid: String) {
        if (uid.isBlank()) return
        syncStateDao.put(
            SyncStateEntity(kind, uid, System.currentTimeMillis() / 1000, deleted = true),
        )
    }

    private suspend fun recentUsage(): List<UsageDay> {
        val today = LocalDate.now().toEpochDay()
        return (0 until USAGE_DAYS).mapNotNull { back ->
            val day = today - back
            val stat = stats.dayStat(day) ?: return@mapNotNull null
            UsageDay(
                date = LocalDate.ofEpochDay(day).toString(),
                totalMinutes = stat.totalScreenMinutes,
                blockShownCount = stat.blockShownCount,
                overrideCount = stat.overrideCount,
            )
        }
    }

    // ---- 受け取ったものを入れる --------------------------------------------

    private suspend fun apply(
        settings: SyncSettings,
        incomingTags: List<Envelope>,
        incomingApps: List<Envelope>,
        incomingDevices: List<Envelope>,
        incomingReservations: List<Envelope>,
        incomingCommands: List<Envelope>,
        incomingRuleStates: List<Envelope>,
    ): Int {
        var applied = 0

        for (envelope in incomingTags) {
            val local = syncStateDao.get(SyncKinds.TAGS, envelope.uid)?.updatedAt
            if (decideMerge(envelope, local) != MergeAction.Apply) continue
            val (pkg, tags) = SyncMapper.tagsOf(envelope, PLATFORM) ?: continue
            rules.replaceTags(pkg, tags)
            syncStateDao.put(SyncStateEntity(SyncKinds.TAGS, envelope.uid, envelope.updatedAt, false))
            applied++
        }

        for (envelope in incomingApps) {
            val local = syncStateDao.get(SyncKinds.APPS, envelope.uid)?.updatedAt
            if (decideMerge(envelope, local) != MergeAction.Apply) continue
            val info = SyncMapper.appOf(envelope) ?: continue
            AppLabels.remember(context, info)
            syncStateDao.put(SyncStateEntity(SyncKinds.APPS, envelope.uid, envelope.updatedAt, false))
            applied++
        }

        // ---- 端末の名簿 ----------------------------------------------
        if (incomingDevices.isNotEmpty()) {
            var roster = settingsStore.devices.first()
            for (envelope in incomingDevices) {
                val info = SyncMapper.deviceOf(envelope) ?: continue
                roster = roster.filterNot { it.deviceId == info.deviceId } + info
                syncStateDao.put(
                    SyncStateEntity(SyncKinds.DEVICES, envelope.uid, envelope.updatedAt, false),
                )
                applied++
            }
            settingsStore.setDevices(roster)
        }

        // ---- ルールが効いているか ---------------------------------------
        //
        // **上書きで持ちます。** 締め切りつきの値なので、古いものを残す意味が無い。
        // 自分のぶんは自分が書くので、受け取ったぶんだけ入れ替える
        if (incomingRuleStates.isNotEmpty()) {
            val now = System.currentTimeMillis() / 1000
            var states = settingsStore.ruleStates.first()
            for (envelope in incomingRuleStates) {
                val local = syncStateDao.get(SyncKinds.RULE_STATES, envelope.uid)?.updatedAt
                if (decideMerge(envelope, local) != MergeAction.Apply) continue
                val state = SyncMapper.ruleStateOf(envelope) ?: continue
                if (state.deviceId == settings.deviceId) continue
                states = states.filterNot { it.uid == state.uid } + state
                syncStateDao.put(
                    SyncStateEntity(SyncKinds.RULE_STATES, envelope.uid, envelope.updatedAt, false),
                )
                applied++
            }
            settingsStore.setRuleStates(RuleStates.prune(states, now))
        }

        // ---- 予約 ------------------------------------------------------
        if (incomingReservations.isNotEmpty()) {
            var list = settingsStore.reservations.first()
            for (envelope in incomingReservations) {
                val local = syncStateDao.get(SyncKinds.RESERVATIONS, envelope.uid)?.updatedAt
                when (decideMerge(envelope, local)) {
                    MergeAction.Skip -> continue

                    MergeAction.Delete -> {
                        list = list.filterNot { it.uid == envelope.uid }
                        syncStateDao.put(
                            SyncStateEntity(SyncKinds.RESERVATIONS, envelope.uid, envelope.updatedAt, true),
                        )
                        applied++
                    }

                    MergeAction.Apply -> {
                        val reservation = SyncMapper.reservationOf(envelope) ?: continue
                        val existing = list.firstOrNull { it.uid == envelope.uid }
                        val placed = reservation.copy(id = existing?.id ?: 0L)
                        list = list.filterNot { it.uid == envelope.uid } + placed
                        syncStateDao.put(
                            SyncStateEntity(SyncKinds.RESERVATIONS, envelope.uid, envelope.updatedAt, false),
                        )
                        applied++
                    }
                }
            }
            settingsStore.setReservations(list)
        }

        // ---- 頼みごと --------------------------------------------------
        // 置くだけ。通すかどうかは DopaRuntime が自分の関門で決める
        if (incomingCommands.isNotEmpty()) {
            var list = settingsStore.commands.first()
            for (envelope in incomingCommands) {
                val local = syncStateDao.get(SyncKinds.COMMANDS, envelope.uid)?.updatedAt
                when (decideMerge(envelope, local)) {
                    MergeAction.Skip -> continue

                    MergeAction.Delete -> {
                        list = list.filterNot { it.uid == envelope.uid }
                        syncStateDao.put(
                            SyncStateEntity(SyncKinds.COMMANDS, envelope.uid, envelope.updatedAt, true),
                        )
                        applied++
                    }

                    MergeAction.Apply -> {
                        val command = SyncMapper.commandOf(envelope) ?: continue
                        list = list.filterNot { it.uid == envelope.uid } + command
                        syncStateDao.put(
                            SyncStateEntity(SyncKinds.COMMANDS, envelope.uid, envelope.updatedAt, false),
                        )
                        applied++
                    }
                }
            }
            // 終わったものは少しだけ残す(送った側に「済み」を出すため)
            val cutoff = System.currentTimeMillis() / 1000 - COMMAND_KEEP_SEC
            settingsStore.setCommands(
                list.filterNot { !it.isOpen && it.handledAtSec in 1 until cutoff },
            )
        }

        return applied
    }

    private companion object {
        const val PLATFORM = AppInfo.ANDROID

        /** 送る実績の日数。 */
        const val USAGE_DAYS = 14

        /** 墓標を置いておく長さ。長く寝ていた端末が復活させない程度に長く。 */
        const val TOMBSTONE_KEEP_SEC = 90L * 24 * 60 * 60

        /** 終わった頼みごとを残しておく長さ。送った側の画面に「済み」を出すため。 */
        const val COMMAND_KEEP_SEC = 3L * 24 * 60 * 60
    }
}
