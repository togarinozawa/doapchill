package com.dopachiru.desktop.data

import com.dopachiru.core.sync.AppInfo
import com.dopachiru.core.sync.Envelope
import com.dopachiru.core.sync.MergeAction
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
 * 止まって起きるのは「別の端末で足したルールがまだ届かない」だけです。
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

    /** 1往復。**呼ぶ側が別スレッドに逃がしてください** ── 素直に待ちます。 */
    fun run(
        file: RuleFile,
        settings: SyncSettings,
        usage: List<UsageDay>,
        onApply: (RuleFile) -> Unit,
        onSettings: (SyncSettings) -> Unit,
    ): Outcome {
        if (!settings.enabled || !settings.isConfigured) return Outcome.NotConfigured

        val api = SyncApi(settings.baseUrl, settings.token)
        val outgoing = collect(file)

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

        var next = file
        var pulled = 0

        for (envelope in response.of(SyncKinds.RULES)) {
            val localAt = next.stampOf(SyncKinds.RULES, envelope.uid)?.updatedAt
            when (decideMerge(envelope, localAt)) {
                MergeAction.Skip -> Unit

                MergeAction.Delete -> {
                    next = next.copy(rules = next.rules.filterNot { it.uid == envelope.uid })
                        .withStamp(SyncKinds.RULES, envelope.uid, SyncStamp(envelope.updatedAt, true))
                    pulled++
                }

                MergeAction.Apply -> {
                    val rule = SyncMapper.ruleOf(envelope) ?: continue
                    // 手元にあれば番号を引き継ぐ。変わると罰や記録の紐付けが切れる
                    val existing = next.rules.firstOrNull { it.uid == envelope.uid }
                    val placed = rule.copy(id = existing?.id ?: next.nextId)
                    next = next.copy(
                        rules = if (existing == null) {
                            next.rules + placed
                        } else {
                            next.rules.map { if (it.uid == envelope.uid) placed else it }
                        },
                        nextId = if (existing == null) next.nextId + 1 else next.nextId,
                    ).withStamp(SyncKinds.RULES, envelope.uid, SyncStamp(envelope.updatedAt, false))
                    pulled++
                }
            }
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

        // 古い墓標を落とす。長く寝ていた端末が復活させない程度には残す
        val cutoff = nowSec() - TOMBSTONE_KEEP_SEC
        next = next.copy(
            syncState = next.syncState.filterNot { (_, s) -> s.deleted && s.updatedAt < cutoff },
        )
        onApply(next)

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
    private fun collect(file: RuleFile): Map<String, List<Envelope>> {
        val rules = ArrayList<Envelope>()
        val live = HashSet<String>()

        for (rule in file.rules) {
            if (rule.uid.isBlank()) continue
            live += rule.uid
            rules += SyncMapper.ruleEnvelope(
                rule,
                file.stampOf(SyncKinds.RULES, rule.uid)?.updatedAt ?: nowSec(),
            )
        }
        val prefix = SyncKinds.RULES + "|"
        for ((key, stamp) in file.syncState) {
            if (!stamp.deleted || !key.startsWith(prefix)) continue
            val uid = key.removePrefix(prefix)
            if (uid in live) continue
            rules += Envelope(uid = uid, updatedAt = stamp.updatedAt, deleted = true)
        }

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

        return mapOf(SyncKinds.RULES to rules, SyncKinds.TAGS to tags, SyncKinds.APPS to apps)
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
