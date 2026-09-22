package com.dopachiru.data

import com.dopachiru.core.DopaCore
import com.dopachiru.core.model.ActionSpec
import com.dopachiru.core.model.Clause
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.RuleMigrations
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.data.db.AppTagDao
import com.dopachiru.data.db.AppTagEntity
import com.dopachiru.data.db.RuleDao
import com.dopachiru.data.db.RuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.flow.map

/** Room の行と core の [Rule] を相互変換する。 */
class RuleRepository(
    private val ruleDao: RuleDao,
    private val appTagDao: AppTagDao,
) {
    /**
     * 画面と判定が読むルール。**古い形はここで読み替える**([RuleMigrations])。
     *
     * 書き戻すのは次に保存したときだけ。読むたびに通すのは、画面と判定で
     * 見えかたが食い違わないようにするため。
     */
    val rules: Flow<List<Rule>> = ruleDao.observeAll()
        .map { rows -> RuleMigrations.upgradeAll(rows.map { it.toRule() }) }

    val tagsByPackage: Flow<Map<String, Set<String>>> = appTagDao.observeAll().map { rows ->
        rows.groupBy({ it.packageName }, { it.tag }).mapValues { it.value.toSet() }
    }

    val tags: Flow<List<String>> = appTagDao.observeTags()

    suspend fun getAll(): List<Rule> = RuleMigrations.upgradeAll(ruleDao.getAll().map { it.toRule() })

    suspend fun getById(id: Long): Rule? = ruleDao.getById(id)?.toRule()?.let { RuleMigrations.upgrade(it) }

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

    /**
     * 期限切れのルールを落とす。
     *
     * **[delete] を通すので墓標が残ります。** 行を消すだけだと、期限を知らない
     * 別の端末が次の同期で送り返してきて、消えたはずの枠が生き返ります。
     *
     * @return 落とした数。
     */
    suspend fun purgeExpired(nowSec: Long = System.currentTimeMillis() / 1000): Int {
        val dead = ruleDao.getAll().filter { it.expiresAtSec in 1..nowSec }
        dead.forEach { delete(it.id) }
        return dead.size
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) =
        ruleDao.setEnabled(id, enabled, System.currentTimeMillis() / 1000)

    suspend fun addTag(packageName: String, tag: String) =
        appTagDao.insert(AppTagEntity(packageName, tag))

    suspend fun removeTag(packageName: String, tag: String) =
        appTagDao.delete(AppTagEntity(packageName, tag))

    suspend fun deleteTag(tag: String) = appTagDao.deleteTag(tag)

    /**
     * そのアプリのタグを、渡された集合で**まるごと置き換える**。
     *
     * 同期で受け取ったものを入れるときに使います。足し引きではなく置き換えなのは、
     * 空の集合がそのまま「全部外した」を表せるようにするため。
     */
    suspend fun replaceTags(packageName: String, tags: Set<String>) {
        val current = appTagDao.getAll().filter { it.packageName == packageName }.map { it.tag }.toSet()
        (current - tags).forEach { appTagDao.delete(AppTagEntity(packageName, it)) }
        (tags - current).forEach { appTagDao.insert(AppTagEntity(packageName, it)) }
    }

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
    // 空文字は「この機能より前に作った行」。罰なしに落とす
    consequence = consequenceJson.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { DopaCore.json.decodeFromString(Consequence.serializer(), it) }
                .getOrDefault(Consequence.NONE)
        }
        ?: Consequence.NONE,
    devices = devicesCsv.split('\t').filter { it.isNotBlank() }.toSet(),
    expiresAtSec = expiresAtSec,
    // 読めなければ「組は1つだけ」に倒す。壊れた2組目でルール全体を失わない
    extraClauses = decodeClauses(extraClausesJson),
    extraActions = decodeActions(extraActionsJson),
)

private val clauseListSerializer = ListSerializer(Clause.serializer())
private val actionListSerializer = ListSerializer(ActionSpec.serializer())

private fun decodeClauses(json: String): List<Clause> =
    if (json.isBlank()) emptyList()
    else runCatching { DopaCore.json.decodeFromString(clauseListSerializer, json) }
        .getOrDefault(emptyList())

private fun decodeActions(json: String): List<ActionSpec> =
    if (json.isBlank()) emptyList()
    else runCatching { DopaCore.json.decodeFromString(actionListSerializer, json) }
        .getOrDefault(emptyList())

fun Rule.toEntity(createdAt: Long, updatedAt: Long): RuleEntity = RuleEntity(
    id = id,
    uid = uid,
    name = name,
    enabled = enabled,
    targetJson = DopaCore.json.encodeToString(Target.serializer(), target),
    conditionJson = DopaCore.encodeCondition(condition),
    actionId = actionId,
    actionParamsJson = actionParams.encode(),
    consequenceJson = if (consequence == Consequence.NONE) {
        ""
    } else {
        DopaCore.json.encodeToString(Consequence.serializer(), consequence)
    },
    devicesCsv = devices.filter { it.isNotBlank() }.joinToString("\t"),
    expiresAtSec = expiresAtSec,
    // 空のときは空文字。"[]" を書くと、組を持たない行と持つ行が見分けづらい
    extraClausesJson = if (extraClauses.isEmpty()) {
        ""
    } else {
        DopaCore.json.encodeToString(clauseListSerializer, extraClauses)
    },
    extraActionsJson = if (extraActions.isEmpty()) {
        ""
    } else {
        DopaCore.json.encodeToString(actionListSerializer, extraActions)
    },
    createdAt = createdAt,
    updatedAt = updatedAt,
)
