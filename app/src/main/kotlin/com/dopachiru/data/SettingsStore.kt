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
import com.dopachiru.core.model.FocusSettings
import com.dopachiru.core.points.PointPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
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
