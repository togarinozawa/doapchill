package com.dopachiru.data

import com.dopachiru.core.DopaCore
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Lockouts
import com.dopachiru.core.model.Target
import com.dopachiru.data.db.LockoutDao
import com.dopachiru.data.db.LockoutEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * ルールを破った罰として閉まっている封鎖の管理。
 *
 * 判定はブロック判定と同じ頻度で呼ばれるのでメモリ上のキャッシュから引き、
 * 再起動をまたげるように DB にも書く。宣言や使用時間と同じ作りにしてある。
 */
class LockoutRepository(
    private val dao: LockoutDao,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var cache: List<Lockout> = emptyList()

    val active: Flow<List<Lockout>> =
        dao.observeActive(0L).map { rows ->
            Lockouts.prune(rows.map { it.toLockout() }, nowSec())
        }

    suspend fun warmUp() {
        val now = nowSec()
        dao.purgeBefore(now)
        cache = dao.activeAt(now).map { it.toLockout() }
    }

    /** いま効いている封鎖。判定から同期的に呼ばれる。 */
    fun current(nowSec: Long = nowSec()): List<Lockout> {
        val pruned = Lockouts.prune(cache, nowSec)
        if (pruned.size != cache.size) cache = pruned
        return pruned
    }

    /**
     * 罰を科す。
     *
     * DB 書き込みを待たずに効かせる。押し切った直後に効かないと、
     * そのまま使い続けられて罰の意味が無い。
     */
    fun impose(target: Target, minutes: Int, reason: String) {
        if (minutes <= 0) return
        val now = nowSec()
        val lockout = Lockout(
            target = target,
            untilEpochSec = now + minutes * 60L,
            reason = reason,
            createdAtEpochSec = now,
        )
        cache = cache + lockout
        scope.launch { dao.insert(lockout.toEntity()) }
    }

    /** 期限切れを掃除する。常駐サービスの定期処理から呼ぶ。 */
    fun purgeExpired() {
        val now = nowSec()
        val pruned = Lockouts.prune(cache, now)
        if (pruned.size == cache.size) return
        cache = pruned
        scope.launch { dao.purgeBefore(now) }
    }

    /** 開発ツール専用。 */
    fun clearAll() {
        cache = emptyList()
        scope.launch { dao.deleteAll() }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000
}

private fun LockoutEntity.toLockout(): Lockout = Lockout(
    id = id,
    target = runCatching { DopaCore.json.decodeFromString(Target.serializer(), targetJson) }
        // 範囲が読めない封鎖を「全部閉まる」に倒すと、壊れた行1つで端末が詰む。
        // 罰が緩むほうがまだましなので、何にも当たらない範囲にする
        .getOrDefault(Target()),
    untilEpochSec = untilEpochSec,
    reason = reason,
    createdAtEpochSec = createdAtEpochSec,
)

private fun Lockout.toEntity(): LockoutEntity = LockoutEntity(
    id = id,
    targetJson = DopaCore.json.encodeToString(Target.serializer(), target),
    untilEpochSec = untilEpochSec,
    reason = reason,
    createdAtEpochSec = createdAtEpochSec,
)
