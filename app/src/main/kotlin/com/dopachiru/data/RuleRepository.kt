package com.dopachiru.data

import com.dopachiru.core.DopaCore
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.data.db.AppTagDao
import com.dopachiru.data.db.AppTagEntity
import com.dopachiru.data.db.RuleDao
import com.dopachiru.data.db.RuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room の行と core の [Rule] を相互変換する。 */
class RuleRepository(
    private val ruleDao: RuleDao,
    private val appTagDao: AppTagDao,
) {
    val rules: Flow<List<Rule>> = ruleDao.observeAll().map { rows -> rows.map { it.toRule() } }

    val tagsByPackage: Flow<Map<String, Set<String>>> = appTagDao.observeAll().map { rows ->
        rows.groupBy({ it.packageName }, { it.tag }).mapValues { it.value.toSet() }
    }

    val tags: Flow<List<String>> = appTagDao.observeTags()

    suspend fun getAll(): List<Rule> = ruleDao.getAll().map { it.toRule() }

    suspend fun getById(id: Long): Rule? = ruleDao.getById(id)?.toRule()

    suspend fun currentTagsByPackage(): Map<String, Set<String>> =
        appTagDao.getAll().groupBy({ it.packageName }, { it.tag }).mapValues { it.value.toSet() }

    suspend fun upsert(rule: Rule): Long {
        val now = System.currentTimeMillis() / 1000
        return if (rule.id == 0L) {
            ruleDao.insert(rule.withUid().toEntity(createdAt = now, updatedAt = now))
        } else {
            val existing = ruleDao.getById(rule.id)
            // 既存の uid は絶対に振り直さない。振り直すと、他の端末からは
            // 「消えて別のものが増えた」ように見える
            val uid = existing?.uid?.takeIf { it.isNotBlank() } ?: rule.withUid().uid
            ruleDao.update(
                rule.copy(uid = uid).toEntity(createdAt = existing?.createdAt ?: now, updatedAt = now)
            )
            rule.id
        }
    }

    /**
     * uid の無い古いルールに振る。
     *
     * ver.0.4 以前に作ったルールには uid が無い。同期を始める前に一度だけ通す。
     */
    suspend fun backfillUids() {
        val now = System.currentTimeMillis() / 1000
        ruleDao.getAll()
            .filter { it.uid.isBlank() }
            .forEach { ruleDao.update(it.copy(uid = newUid(), updatedAt = now)) }
    }

    suspend fun delete(id: Long) = ruleDao.deleteById(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) =
        ruleDao.setEnabled(id, enabled, System.currentTimeMillis() / 1000)

    suspend fun addTag(packageName: String, tag: String) =
        appTagDao.insert(AppTagEntity(packageName, tag))

    suspend fun removeTag(packageName: String, tag: String) =
        appTagDao.delete(AppTagEntity(packageName, tag))

    suspend fun deleteTag(tag: String) = appTagDao.deleteTag(tag)
}

/** 新しい同期用 ID。 */
fun newUid(): String = java.util.UUID.randomUUID().toString()

private fun Rule.withUid(): Rule = if (uid.isBlank()) copy(uid = newUid()) else this

fun RuleEntity.toRule(): Rule = Rule(
    id = id,
    uid = uid,
    name = name,
    enabled = enabled,
    target = runCatching { DopaCore.json.decodeFromString(Target.serializer(), targetJson) }
        .getOrDefault(Target()),
    condition = runCatching { DopaCore.decodeCondition(conditionJson) }
        .getOrDefault(ConditionNode.AllOf()),
    actionId = actionId,
    actionParams = Params.decode(actionParamsJson),
)

fun Rule.toEntity(createdAt: Long, updatedAt: Long): RuleEntity = RuleEntity(
    id = id,
    uid = uid,
    name = name,
    enabled = enabled,
    targetJson = DopaCore.json.encodeToString(Target.serializer(), target),
    conditionJson = DopaCore.encodeCondition(condition),
    actionId = actionId,
    actionParamsJson = actionParams.encode(),
    createdAt = createdAt,
    updatedAt = updatedAt,
)
