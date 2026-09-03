package com.dopachiru.data

import android.content.Context
import com.dopachiru.core.model.Rule
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
        val outgoing = collect()

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
            response.of(SyncKinds.RULES),
            response.of(SyncKinds.TAGS),
            response.of(SyncKinds.APPS),
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

    private suspend fun collect(): Map<String, List<Envelope>> {
        val local = rules.allWithUpdatedAt()
        val ruleEnvelopes = ArrayList<Envelope>()
        val liveUids = HashSet<String>()

        for ((rule, updatedAt) in local) {
            if (rule.uid.isBlank()) continue
            liveUids += rule.uid
            ruleEnvelopes += SyncMapper.ruleEnvelope(rule, updatedAt)
        }
        // 消したものの墓標。手元に生き返っているものは送らない
        for (state in syncStateDao.ofKind(SyncKinds.RULES)) {
            if (!state.deleted || state.uid in liveUids) continue
            ruleEnvelopes += Envelope(uid = state.uid, updatedAt = state.updatedAt, deleted = true)
        }

        val tagsByPackage = rules.currentTagsByPackage()
        val tagEnvelopes = tagsByPackage.map { (pkg, tags) ->
            SyncMapper.tagsEnvelope(PLATFORM, pkg, tags, stampFor(SyncKinds.TAGS, "$PLATFORM:$pkg"))
        }

        // 名札は、手元のルールとタグが触れているアプリぶんだけ。
        // 端末に入っている全アプリを送るのは、要らないうえに知られすぎる
        val referenced = buildSet {
            addAll(tagsByPackage.keys)
            local.forEach { (rule, _) ->
                addAll(rule.target.packages)
                addAll(rule.target.exceptPackages)
            }
        }
        val appEnvelopes = referenced.map { pkg ->
            val info = AppInfo(pkg, InstalledApps.labelOf(context, pkg), PLATFORM)
            SyncMapper.appEnvelope(info, stampFor(SyncKinds.APPS, info.uid))
        }

        return mapOf(
            SyncKinds.RULES to ruleEnvelopes,
            SyncKinds.TAGS to tagEnvelopes,
            SyncKinds.APPS to appEnvelopes,
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
        incomingRules: List<Envelope>,
        incomingTags: List<Envelope>,
        incomingApps: List<Envelope>,
    ): Int {
        var applied = 0
        val localRuleTimes = rules.updatedAtByUid()

        for (envelope in incomingRules) {
            when (decideMerge(envelope, localRuleTimes[envelope.uid])) {
                MergeAction.Skip -> Unit

                MergeAction.Delete -> {
                    rules.deleteByUid(envelope.uid)
                    // こちらでも墓標を残す。残さないと次の同期で送り返して往復する
                    syncStateDao.put(
                        SyncStateEntity(SyncKinds.RULES, envelope.uid, envelope.updatedAt, true),
                    )
                    applied++
                }

                MergeAction.Apply -> {
                    val rule = SyncMapper.ruleOf(envelope) ?: continue
                    // 同じ uid が手元にあればその番号を引き継ぐ。番号が変わると、
                    // 罰や記録がぶら下がっている先を見失う
                    val existingId = rules.getAll().firstOrNull { it.uid == rule.uid }?.id ?: 0L
                    rules.upsertFromSync(rule.copy(id = existingId), envelope.updatedAt)
                    syncStateDao.remove(SyncKinds.RULES, envelope.uid)
                    applied++
                }
            }
        }

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

        return applied
    }

    private companion object {
        const val PLATFORM = AppInfo.ANDROID

        /** 送る実績の日数。 */
        const val USAGE_DAYS = 14

        /** 墓標を置いておく長さ。長く寝ていた端末が復活させない程度に長く。 */
        const val TOMBSTONE_KEEP_SEC = 90L * 24 * 60 * 60
    }
}
