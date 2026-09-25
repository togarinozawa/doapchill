package com.dopachiru.data

import com.dopachiru.core.model.BookingCheck
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.ReservationPolicy
import com.dopachiru.core.model.ReservationRules
import com.dopachiru.core.model.Reservations
import com.dopachiru.core.model.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 予約の管理。
 *
 * 「いま予約の中か」の判定はブロック判定と同じ頻度で呼ばれるのでメモリから引き、
 * 端末をまたいで残せるように [SettingsStore] に JSON で書き戻す。
 * 封鎖(罰・集中)と作りをそろえてある。
 */
class ReservationRepository(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var cache: List<Reservation> = emptyList()

    private val _flow = MutableStateFlow<List<Reservation>>(emptyList())

    /** 画面表示用。終わった予約は落としてある。 */
    val reservations: StateFlow<List<Reservation>> = _flow.asStateFlow()

    init {
        // 同期は店([SettingsStore])に直接書く。追いかけないと、ほかの端末から取られた枠が
        // 次に起動するまで効かず、そのあいだにこちらで予約すると古い手元の一覧で上書きしてしまう
        scope.launch {
            store.reservations.collect { stored ->
                val pruned = Reservations.prune(stored, nowSec())
                cache = pruned
                _flow.value = pruned
            }
        }
    }

    /** 起動直後に読み込む。 */
    suspend fun warmUp() {
        val now = nowSec()
        val loaded = Reservations.prune(store.reservations.first(), now)
        cache = loaded
        _flow.value = loaded
        // 掃除したぶんを書き戻す
        scope.launch { store.setReservations(loaded) }
    }

    /**
     * そのアプリが、いま予約の時間帯の中にいるか。判定から同期的に呼ばれる。
     *
     * @param deviceId この端末。別の端末に向けて取られた枠は効かせない。
     */
    fun covers(
        packageName: String,
        tagsOfApp: Set<String>,
        url: String? = null,
        nowSec: Long = nowSec(),
        deviceId: String = "",
    ): Boolean {
        val pruned = Reservations.prune(cache, nowSec)
        if (pruned.size != cache.size) {
            cache = pruned
            _flow.value = pruned
            scope.launch { store.setReservations(pruned) }
        }
        return Reservations.covers(pruned, packageName, tagsOfApp, nowSec, url, deviceId)
    }

    /**
     * 予約を1つ取る。開始が [minLeadMinutes] より手前なら弾く(直前予約は封じる)。
     *
     * @return 取れたら予約。弾いたら null。
     */
    fun book(
        target: Target,
        startEpochSec: Long,
        endEpochSec: Long,
        minLeadMinutes: Int,
        note: String = "",
        devices: Set<String> = emptySet(),
        policyId: String = "",
    ): Reservation? {
        val now = nowSec()
        if (startEpochSec < now + minLeadMinutes * 60L) return null
        if (endEpochSec <= startEpochSec) return null
        val reservation = Reservation(
            uid = UUID.randomUUID().toString(),
            target = target,
            startEpochSec = startEpochSec,
            endEpochSec = endEpochSec,
            note = note,
            devices = devices,
            policyId = policyId,
        )
        val next = Reservations.prune(cache, now) + reservation
        cache = next
        _flow.value = next
        scope.launch {
            store.setReservations(next)
            // 変えた時刻を残さないと、据え置きの時刻のまま送られてサーバーに弾かれる
            onChanged(reservation.uid, false)
        }
        return reservation
    }

    /**
     * 型から枠を取る。**判定は core([ReservationRules.check])に任せる。**
     *
     * 断った理由まで返すのは、「取れません」とだけ言われても何を直せば
     * いいのか分からないため。
     */
    fun bookUnder(
        policy: ReservationPolicy,
        startEpochSec: Long,
        endEpochSec: Long,
        devices: Set<String> = emptySet(),
    ): BookingCheck {
        val now = nowSec()
        val existing = ReservationRules.bookedUnder(policy, cache, now, devices.singleOrNull().orEmpty())
        val verdict = ReservationRules.check(policy, existing, startEpochSec, endEpochSec, now)
        if (verdict is BookingCheck.Refused) return verdict

        val reservation = Reservation(
            uid = UUID.randomUUID().toString(),
            target = policy.target,
            startEpochSec = startEpochSec,
            endEpochSec = endEpochSec,
            note = policy.label,
            devices = devices,
            policyId = policy.id,
        )
        val next = Reservations.prune(cache, now) + reservation
        cache = next
        _flow.value = next
        scope.launch {
            store.setReservations(next)
            onChanged(reservation.uid, false)
        }
        return BookingCheck.Ok
    }

    /** 予約を取り消す。まだ始まっていないものだけでなく、いま有効なものも消せる。 */
    fun cancel(uid: String) {
        val next = cache.filterNot { it.uid == uid }
        cache = next
        _flow.value = next
        scope.launch {
            store.setReservations(next)
            // 墓標を残す。残さないと、次の同期で別の端末から送り返されて生き返る
            onChanged(uid, true)
        }
    }

    /**
     * 変えたことを同期側に伝える差し込み口。
     *
     * [SyncManager] を直に持たせないのは、予約の置き場所が同期の都合を知らずに
     * 済むようにするため(同期を切っていても予約は動く)。
     */
    var onChanged: suspend (uid: String, deleted: Boolean) -> Unit = { _, _ -> }

    /** いまと、これからの予約。画面に並べる用。開始の早い順。 */
    fun upcoming(nowSec: Long = nowSec()): List<Reservation> =
        Reservations.prune(cache, nowSec).sortedBy { it.startEpochSec }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000
}
