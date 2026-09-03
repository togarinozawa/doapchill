package com.dopachiru.data

import com.dopachiru.core.DopaCore
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.data.db.AppTagDao
import com.dopachiru.data.db.AppTagEntity
import com.dopachiru.data.db.RuleDao
import com.dopachiru.data.db.RuleEntity
import com.dopachiru.data.db.SyncStateDao
import com.dopachiru.data.db.SyncStateEntity
import com.dopachiru.core.sync.SyncKinds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room の行と core の [Rule] を相互変換する。 */
class RuleRepository(
    private val ruleDao: RuleDao,
    private val appTagDao: AppTagDao,
    /**
     * 同期の覚え書き。同期を使っていなくても書きます ──
     * **後から同期を入れたときに、それまでの削除が抜け落ちない**ように。
     */
    private val syncStateDao: SyncStateDao? = null,
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

    /**
     * ルールを消す。
     *
     * 行を消すだけでは、次の同期で「そんなものは無かった」ようにしか見えません。
     * **別の端末が送り返してきて生き返る**ので、消したこと自体を墓標に残します。
     */
    suspend fun delete(id: Long) {
        val uid = ruleDao.getById(id)?.uid
        ruleDao.deleteById(id)
        if (!uid.isNullOrBlank()) {
            syncStateDao?.put(
                SyncStateEntity(
                    kind = SyncKinds.RULES,
                    uid = uid,
                    updatedAt = System.currentTimeMillis() / 1000,
                    deleted = true,
                ),
            )
        }
    }

    /** 同期で受け取った削除を反映する。墓標は呼ぶ側が書きます。 */
    suspend fun deleteByUid(uid: String) {
        val row = ruleDao.getAll().firstOrNull { it.uid == uid } ?: return
        ruleDao.deleteById(row.id)
    }

    /** uid → 最終更新(秒)。突き合わせで「どちらが新しいか」を見るために使う。 */
    suspend fun updatedAtByUid(): Map<String, Long> =
        ruleDao.getAll().filter { it.uid.isNotBlank() }.associate { it.uid to it.updatedAt }

    /** 行と更新時刻の組。同期に送るときに要る。 */
    suspend fun allWithUpdatedAt(): List<Pair<Rule, Long>> =
        ruleDao.getAll().map { it.toRule() to it.updatedAt }

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

    /**
     * 同期で受け取ったルールを入れる。
     *
     * 普通の [upsert] と違って、**更新時刻を今にしません。** 向こうが付けた時刻を
     * そのまま残さないと、受け取った瞬間にこちらのほうが新しくなり、
     * 次の同期で相手に押し返してしまいます(往復が止まらなくなる)。
     */
    suspend fun upsertFromSync(rule: Rule, updatedAt: Long) {
        val existing = if (rule.id == 0L) null else ruleDao.getById(rule.id)
        val entity = rule.withUid().toEntity(
            createdAt = existing?.createdAt ?: updatedAt,
            updatedAt = updatedAt,
        )
        if (existing == null) ruleDao.insert(entity.copy(id = 0L)) else ruleDao.update(entity)
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
    consequenceJson = if (consequence == Consequence.NONE) {
        ""
    } else {
        DopaCore.json.encodeToString(Consequence.serializer(), consequence)
    },
    createdAt = createdAt,
    updatedAt = updatedAt,
)
