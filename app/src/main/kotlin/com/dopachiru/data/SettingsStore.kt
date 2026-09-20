package com.dopachiru.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dopachiru.core.DopaCore
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.model.Command
import com.dopachiru.core.model.FocusSchedule
import com.dopachiru.core.model.FocusSchedules
import com.dopachiru.core.model.FocusSettings
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.ReservationPolicy
import com.dopachiru.core.model.ReservationRules
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.core.sync.DeviceInfo
import com.dopachiru.core.sync.RuleState
import com.dopachiru.core.sync.SyncSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dopa_settings")

/**
 * アプリ全体の設定。
 *
 * ルール変更に課すゲートは全ルール共通で1組だけ持つ。
 * ルールごとに変えたくなったら RuleEntity 側に持たせればよいが、
 * ver.1 では「自分に課す縛りは1つ」のほうが運用しやすいと判断した。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val setupDone = booleanPreferencesKey("setup_done")
        val passwordHash = stringPreferencesKey("password_hash")
        val passwordSalt = stringPreferencesKey("password_salt")
        val gatesJson = stringPreferencesKey("gates_json")
        val blockHomeScreen = booleanPreferencesKey("block_home_screen")
        val showOnUnlock = booleanPreferencesKey("show_on_unlock")
        val unlockMessage = stringPreferencesKey("unlock_message")
        val growthName = stringPreferencesKey("growth_name")
        val selfDefense = booleanPreferencesKey("self_defense")
        val batterySaver = booleanPreferencesKey("battery_saver")
        val studyPrepMinutes = intPreferencesKey("study_prep_minutes")
        val pointPolicyJson = stringPreferencesKey("point_policy_json")
        val passUntilEpochSec = longPreferencesKey("pass_until_epoch_sec")
        val focusSettingsJson = stringPreferencesKey("focus_settings_json")
        val syncSettingsJson = stringPreferencesKey("sync_settings_json")
        val reservationsJson = stringPreferencesKey("reservations_json")
        val deviceName = stringPreferencesKey("device_name")
        val devicesJson = stringPreferencesKey("devices_json")
        val commandsJson = stringPreferencesKey("commands_json")
        val ruleStatesJson = stringPreferencesKey("rule_states_json")
        val focusSchedulesJson = stringPreferencesKey("focus_schedules_json")
        val focusScheduleRunsJson = stringPreferencesKey("focus_schedule_runs_json")
        val reservationPoliciesJson = stringPreferencesKey("reservation_policies_json")
        val reservationLeadMinutes = intPreferencesKey("reservation_lead_minutes")
    }

    val setupDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.setupDone] ?: false }

    val gates: Flow<List<Gate>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.gatesJson] ?: return@map DEFAULT_GATES
        runCatching {
            DopaCore.json.decodeFromString(ListSerializer(Gate.serializer()), raw)
        }.getOrDefault(DEFAULT_GATES)
    }

    val hasPassword: Flow<Boolean> = context.dataStore.data.map { it[Keys.passwordHash] != null }

    /** ホーム画面に戻ったときにも一瞬オーバーレイを被せるか。 */
    val blockHomeScreen: Flow<Boolean> = context.dataStore.data.map { it[Keys.blockHomeScreen] ?: false }

    /** ロック解除の直後にオーバーレイを出すか(待ち受け画面の代替)。 */
    val showOnUnlock: Flow<Boolean> = context.dataStore.data.map { it[Keys.showOnUnlock] ?: true }

    val unlockMessage: Flow<String> =
        context.dataStore.data.map { it[Keys.unlockMessage] ?: "今スマホを開く理由はある?" }

    val growthName: Flow<String> = context.dataStore.data.map { it[Keys.growthName] ?: "めばえ" }

    /**
     * ドパチル自身の設定を開こうとしたときに引き止めるか。
     * 引き止めるだけで、進むことは必ずできる。
     */
    val selfDefense: Flow<Boolean> = context.dataStore.data.map { it[Keys.selfDefense] ?: false }

    suspend fun setSelfDefense(enabled: Boolean) {
        context.dataStore.edit { it[Keys.selfDefense] = enabled }
    }

    /**
     * ドパチル自身の消費を抑えるモード。
     * 判定を見に来る間隔とカレンダーの読み直しが伸びる。
     * ブロックが最大で数十秒遅れることがある代わりに、常駐の消費が減る。
     */
    val batterySaver: Flow<Boolean> = context.dataStore.data.map { it[Keys.batterySaver] ?: false }

    suspend fun setBatterySaver(enabled: Boolean) {
        context.dataStore.edit { it[Keys.batterySaver] = enabled }
    }

    /**
     * 学習予定の何分前から「助走枠」とみなすか。0 で無効。
     *
     * 連携アプリは予定の時間帯しか送ってこない。手前に伸ばすのはこちらの仕事なので、
     * ここを変えるのに向こうの再ビルドは要らない。
     */
    val studyPrepMinutes: Flow<Int> = context.dataStore.data.map {
        it[Keys.studyPrepMinutes] ?: StudyWindowRepository.DEFAULT_PREP_MINUTES
    }

    suspend fun setStudyPrepMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.studyPrepMinutes] = minutes.coerceIn(0, 180) }
    }

    /**
     * ポイントの使い道と相場。
     *
     * 丸ごと JSON で持つ。項目を増やすたびにキーを足していくと、
     * 設定画面と保存先の両方に手を入れることになるため。
     */
    val pointPolicy: Flow<PointPolicy> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.pointPolicyJson] ?: return@map PointPolicy.DEFAULT
        runCatching { DopaCore.json.decodeFromString(PointPolicy.serializer(), raw) }
            .getOrDefault(PointPolicy.DEFAULT)
    }

    suspend fun setPointPolicy(policy: PointPolicy) {
        val encoded = DopaCore.json.encodeToString(PointPolicy.serializer(), policy)
        context.dataStore.edit { it[Keys.pointPolicyJson] = encoded }
    }

    /**
     * 解禁券で制限が止まっている期限。
     *
     * ポイントで買った「全部止まる時間」。過ぎれば勝手に戻るので、
     * 買ったまま解除を忘れて縛りが死ぬことがない。
     */
    val passUntilEpochSec: Flow<Long> =
        context.dataStore.data.map { it[Keys.passUntilEpochSec] ?: 0L }

    /**
     * 集中モードの既定値。
     *
     * ポイントの相場と同じく JSON 1本で持つ ── 欄が増えても
     * DataStore の鍵を足さずに済む。
     */
    val focusSettings: Flow<FocusSettings> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.focusSettingsJson] ?: return@map FocusSettings()
        runCatching { DopaCore.json.decodeFromString(FocusSettings.serializer(), raw) }
            .getOrDefault(FocusSettings())
    }

    suspend fun setFocusSettings(settings: FocusSettings) {
        val encoded = DopaCore.json.encodeToString(FocusSettings.serializer(), settings)
        context.dataStore.edit { it[Keys.focusSettingsJson] = encoded }
    }

    /**
     * 同期の設定。
     *
     * 合言葉をここに持つので、**画面に出すときは伏せます。**
     * JSON 1本なのは、欄が増えても DataStore の鍵を足さずに済むため。
     */
    val syncSettings: Flow<SyncSettings> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.syncSettingsJson] ?: return@map SyncSettings()
        runCatching { DopaCore.json.decodeFromString(SyncSettings.serializer(), raw) }
            .getOrDefault(SyncSettings())
    }

    suspend fun setSyncSettings(settings: SyncSettings) {
        val encoded = DopaCore.json.encodeToString(SyncSettings.serializer(), settings)
        context.dataStore.edit { it[Keys.syncSettingsJson] = encoded }
    }

    /**
     * 予約の一覧。
     *
     * リストを丸ごと JSON 1本で持つ ── 数が少なく、まとめて読み書きするので
     * Room のテーブルにするほどではない。判定からは同期的に読みたいので、
     * 実体のキャッシュは [com.dopachiru.data.ReservationRepository] が持つ。
     */
    val reservations: Flow<List<Reservation>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.reservationsJson] ?: return@map emptyList()
        runCatching {
            DopaCore.json.decodeFromString(ListSerializer(Reservation.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun setReservations(list: List<Reservation>) {
        val encoded = DopaCore.json.encodeToString(ListSerializer(Reservation.serializer()), list)
        context.dataStore.edit { it[Keys.reservationsJson] = encoded }
    }

    /**
     * 名簿に出すこの端末の名前。空なら deviceId がそのまま出る。
     *
     * deviceId と分けてあるのは、**deviceId を変えると実績の見出しが切れる**ため。
     * 呼び名を変えたいだけのときに過去の記録を捨てさせない。
     */
    val deviceName: Flow<String> = context.dataStore.data.map { it[Keys.deviceName] ?: "" }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[Keys.deviceName] = name.trim().take(40) }
    }

    /**
     * 端末の名簿。同期のたびに入れ替わる、ただの控え。
     *
     * 消えても次の同期でまた届くので、Room の表にするほどのものではない。
     */
    val devices: Flow<List<DeviceInfo>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.devicesJson] ?: return@map emptyList()
        runCatching {
            DopaCore.json.decodeFromString(ListSerializer(DeviceInfo.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun setDevices(list: List<DeviceInfo>) {
        val encoded = DopaCore.json.encodeToString(ListSerializer(DeviceInfo.serializer()), list)
        context.dataStore.edit { it[Keys.devicesJson] = encoded }
    }

    /** 端末をまたいだ頼みごと。出したものと受け取ったものの両方。 */
    val commands: Flow<List<Command>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.commandsJson] ?: return@map emptyList()
        runCatching {
            DopaCore.json.decodeFromString(ListSerializer(Command.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun setCommands(list: List<Command>) {
        val encoded = DopaCore.json.encodeToString(ListSerializer(Command.serializer()), list)
        context.dataStore.edit { it[Keys.commandsJson] = encoded }
    }

    /**
     * 「そのルールが、その端末で、いま効いているか」。自分のぶんも他の端末のぶんも。
     *
     * 使いすぎを止めるルールは端末を替えれば逃げられる ── 持ち時間が端末ごとに
     * 1本ずつあるため。効いているという事実を配って塞ぐ。[RuleState]
     */
    val ruleStates: Flow<List<RuleState>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.ruleStatesJson] ?: return@map emptyList()
        runCatching {
            DopaCore.json.decodeFromString(ListSerializer(RuleState.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun setRuleStates(list: List<RuleState>) {
        val encoded = DopaCore.json.encodeToString(ListSerializer(RuleState.serializer()), list)
        context.dataStore.edit { it[Keys.ruleStatesJson] = encoded }
    }

    /**
     * 自分で始めなくても始まる集中の予定。
     *
     * 起動の手間がかかる道具は、起動の手間を払えないときにちょうど効かない ──
     * その穴を埋めるためのもの。[com.dopachiru.core.model.FocusSchedule]
     */
    val focusSchedules: Flow<List<FocusSchedule>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.focusSchedulesJson] ?: return@map emptyList()
        runCatching {
            DopaCore.json.decodeFromString(ListSerializer(FocusSchedule.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun setFocusSchedules(list: List<FocusSchedule>) {
        val encoded = DopaCore.json.encodeToString(
            ListSerializer(FocusSchedule.serializer()),
            list.take(FocusSchedules.MAX),
        )
        context.dataStore.edit { it[Keys.focusSchedulesJson] = encoded }
    }

    /**
     * 予定ごとに、最後に走らせた日(`uid` → `yyyy-MM-dd`)。
     *
     * **同じ日に二度始めないための鍵**です。これが無いと、集中が明けた瞬間に
     * また始まって永久に閉まります ── 出口の無い封鎖は事故です。
     */
    val focusScheduleRuns: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.focusScheduleRunsJson] ?: return@map emptyMap()
        runCatching {
            DopaCore.json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                raw,
            )
        }.getOrDefault(emptyMap())
    }

    suspend fun setFocusScheduleRuns(runs: Map<String, String>) {
        val encoded = DopaCore.json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            runs,
        )
        context.dataStore.edit { it[Keys.focusScheduleRunsJson] = encoded }
    }

    /**
     * 予約できる枠の型。
     *
     * 端末ごとに持つ(同期しない) ── 予約を取るのは型がある端末で、
     * 取れた枠のほうは端末をまたいで配られる。型まで配ると、
     * PC に無いアプリの型がスマホに並ぶことになる。
     */
    val reservationPolicies: Flow<List<ReservationPolicy>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.reservationPoliciesJson] ?: return@map emptyList()
        runCatching {
            DopaCore.json.decodeFromString(ListSerializer(ReservationPolicy.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun setReservationPolicies(list: List<ReservationPolicy>) {
        val encoded = DopaCore.json.encodeToString(
            ListSerializer(ReservationPolicy.serializer()),
            list,
        )
        context.dataStore.edit { it[Keys.reservationPoliciesJson] = encoded }
    }

    /** 予約をいまから何分先からしか取れないか。直前予約を封じる待ち。 */
    val reservationLeadMinutes: Flow<Int> = context.dataStore.data.map {
        it[Keys.reservationLeadMinutes] ?: ReservationRules.MIN_LEAD_MINUTES
    }

    suspend fun setReservationLeadMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.reservationLeadMinutes] = minutes.coerceAtLeast(0) }
    }

    suspend fun setPassUntil(epochSec: Long) {
        context.dataStore.edit { it[Keys.passUntilEpochSec] = epochSec }
    }

    suspend fun setSetupDone(done: Boolean) {
        context.dataStore.edit { it[Keys.setupDone] = done }
    }

    suspend fun setGates(gates: List<Gate>) {
        val encoded = DopaCore.json.encodeToString(ListSerializer(Gate.serializer()), gates)
        context.dataStore.edit { it[Keys.gatesJson] = encoded }
    }

    suspend fun setBlockHomeScreen(enabled: Boolean) {
        context.dataStore.edit { it[Keys.blockHomeScreen] = enabled }
    }

    suspend fun setShowOnUnlock(enabled: Boolean) {
        context.dataStore.edit { it[Keys.showOnUnlock] = enabled }
    }

    suspend fun setUnlockMessage(message: String) {
        context.dataStore.edit { it[Keys.unlockMessage] = message }
    }

    suspend fun setGrowthName(name: String) {
        context.dataStore.edit { it[Keys.growthName] = name }
    }

    suspend fun setPassword(raw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(raw, salt)
        context.dataStore.edit {
            it[Keys.passwordSalt] = salt.toHex()
            it[Keys.passwordHash] = hash
        }
    }

    suspend fun verifyPassword(raw: String): Boolean {
        val prefs = context.dataStore.data.first()
        val saltHex = prefs[Keys.passwordSalt] ?: return false
        val expected = prefs[Keys.passwordHash] ?: return false
        return hash(raw, saltHex.fromHex()) == expected
    }

    private fun hash(raw: String, salt: ByteArray): String {
        val spec = PBEKeySpec(raw.toCharArray(), salt, 120_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        /** 初期状態のゲート。いきなり厳しすぎない程度に、けれど即時変更はさせない。 */
        val DEFAULT_GATES: List<Gate> = listOf(
            Gate.Cooldown(minutes = 30),
            Gate.WriteReason(minLength = 30),
        )
    }
}
