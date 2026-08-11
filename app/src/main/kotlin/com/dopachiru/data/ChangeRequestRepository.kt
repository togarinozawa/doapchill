package com.dopachiru.data

import com.dopachiru.core.DopaCore
import com.dopachiru.core.engine.CalendarState
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.gate.ChangeStatus
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.gate.GatePolicy
import com.dopachiru.core.model.Rule
import com.dopachiru.data.db.ChangeRequestDao
import com.dopachiru.data.db.ChangeRequestEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 起票されたルール変更と、その通過状況。 */
data class PendingChange(
    val entity: ChangeRequestEntity,
    val gates: List<Gate>,
    val remaining: List<Gate>,
) {
    val isReady: Boolean get() = remaining.isEmpty()
    val kind: ChangeKind get() = runCatching { ChangeKind.valueOf(entity.kind) }.getOrDefault(ChangeKind.UPDATE)
    val rule: Rule? get() = runCatching { DopaCore.decodeRule(entity.payloadJson) }.getOrNull()
    val previousRule: Rule? get() = runCatching { DopaCore.decodeRule(entity.previousJson) }.getOrNull()
}

/**
 * ルール変更を「起票 → ゲート通過 → 適用」の流れに乗せる。
 *
 * 変更を即時反映させないことで、衝動的な緩和にクールダウンを挟みつつ、
 * 「何を、なぜ変えたか」を履歴として残す。
 */
class ChangeRequestRepository(
    private val dao: ChangeRequestDao,
    private val ruleRepository: RuleRepository,
    /** カレンダー連動のゲートを判定するために、いまの予定を引く。 */
    private val calendarState: () -> CalendarState = { CalendarState.NONE },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    val all: Flow<List<PendingChange>> = dao.observeAll().map { rows -> rows.map { it.toPending() } }

    val pending: Flow<List<PendingChange>> = dao.observePending().map { rows -> rows.map { it.toPending() } }

    /**
     * 変更を起票する。
     *
     * ルールを「強くする」変更(新規作成・有効化)はゲートを課さず即時適用する。
     * 縛りを増やす方向に摩擦をかけても意味がないため。
     */
    suspend fun request(
        kind: ChangeKind,
        rule: Rule?,
        gates: List<Gate>,
        reason: String = "",
    ): Long {
        if (kind == ChangeKind.CREATE || kind == ChangeKind.ENABLE) {
            rule?.let { applyRule(kind, it) }
            return recordImmediate(kind, rule, reason)
        }

        val previous = rule?.id?.let { ruleRepository.getById(it) }
        val entity = ChangeRequestEntity(
            kind = kind.name,
            targetRuleId = rule?.id,
            payloadJson = rule?.let { DopaCore.encodeRule(it) } ?: "",
            previousJson = previous?.let { DopaCore.encodeRule(it) } ?: "",
            reason = reason,
            createdAtEpochSec = System.currentTimeMillis() / 1000,
            resolvedAtEpochSec = null,
            status = ChangeStatus.PENDING.name,
            clearedGateKeys = "",
            gatesJson = DopaCore.json.encodeToString(ListSerializer(Gate.serializer()), gates),
        )
        return dao.insert(entity)
    }

    /** ゲートを1つ通過させる。 */
    suspend fun clearGate(requestId: Long, gateKey: String) {
        val row = dao.getById(requestId) ?: return
        val cleared = row.clearedGateKeys.split(',').filter { it.isNotBlank() }.toMutableSet()
        cleared += gateKey
        dao.update(row.copy(clearedGateKeys = cleared.joinToString(",")))
    }

    suspend fun setReason(requestId: Long, reason: String) {
        val row = dao.getById(requestId) ?: return
        dao.update(row.copy(reason = reason))
    }

    /** ゲートをすべて通過していれば適用する。適用できたら true。 */
    suspend fun applyIfReady(requestId: Long): Boolean {
        val row = dao.getById(requestId) ?: return false
        val pending = row.toPending()
        if (!pending.isReady) return false

        val rule = pending.rule
        when (pending.kind) {
            ChangeKind.DELETE -> row.targetRuleId?.let { ruleRepository.delete(it) }
            ChangeKind.DISABLE -> row.targetRuleId?.let { ruleRepository.setEnabled(it, false) }
            ChangeKind.ENABLE -> row.targetRuleId?.let { ruleRepository.setEnabled(it, true) }
            ChangeKind.CREATE, ChangeKind.UPDATE -> rule?.let { ruleRepository.upsert(it) }
        }

        dao.update(
            row.copy(
                status = ChangeStatus.APPLIED.name,
                resolvedAtEpochSec = System.currentTimeMillis() / 1000,
            )
        )
        return true
    }

    suspend fun cancel(requestId: Long) {
        val row = dao.getById(requestId) ?: return
        dao.update(
            row.copy(
                status = ChangeStatus.CANCELLED.name,
                resolvedAtEpochSec = System.currentTimeMillis() / 1000,
            )
        )
    }

    private suspend fun applyRule(kind: ChangeKind, rule: Rule) {
        when (kind) {
            ChangeKind.CREATE, ChangeKind.UPDATE -> ruleRepository.upsert(rule)
            ChangeKind.ENABLE -> ruleRepository.setEnabled(rule.id, true)
            else -> Unit
        }
    }

    private suspend fun recordImmediate(kind: ChangeKind, rule: Rule?, reason: String): Long {
        val now = System.currentTimeMillis() / 1000
        return dao.insert(
            ChangeRequestEntity(
                kind = kind.name,
                targetRuleId = rule?.id,
                payloadJson = rule?.let { DopaCore.encodeRule(it) } ?: "",
                previousJson = "",
                reason = reason,
                createdAtEpochSec = now,
                resolvedAtEpochSec = now,
                status = ChangeStatus.APPLIED.name,
                clearedGateKeys = "",
                gatesJson = "[]",
            )
        )
    }

    private fun ChangeRequestEntity.toPending(): PendingChange {
        val gates = runCatching {
            DopaCore.json.decodeFromString(ListSerializer(Gate.serializer()), gatesJson)
        }.getOrDefault(emptyList())

        val cleared = clearedGateKeys.split(',').filter { it.isNotBlank() }.toSet()
        val createdAt = LocalDateTime.ofInstant(Instant.ofEpochSecond(createdAtEpochSec), zone)
        val remaining = if (status == ChangeStatus.PENDING.name) {
            GatePolicy.remaining(gates, cleared, createdAt, LocalDateTime.now(zone), calendarState())
        } else {
            emptyList()
        }
        return PendingChange(this, gates, remaining)
    }
}
