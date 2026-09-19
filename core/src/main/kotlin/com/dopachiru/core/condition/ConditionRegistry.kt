package com.dopachiru.core.condition

/**
 * 使える条件の一覧。
 *
 * ここに登録された条件が、そのまま設定画面の選択肢になる。
 */
object ConditionRegistry {
    private val types = LinkedHashMap<String, ConditionType>()

    fun register(vararg newTypes: ConditionType) {
        for (type in newTypes) {
            val previous = types.put(type.id, type)
            check(previous == null) { "条件IDが重複している: ${type.id}" }
        }
    }

    operator fun get(id: String): ConditionType? = types[id]

    fun requireType(id: String): ConditionType =
        types[id] ?: error("未登録の条件ID: $id")

    fun all(): List<ConditionType> = types.values.toList()

    /**
     * いま新しく選べる条件だけ。設定画面の選択肢はこちらを使う。
     *
     * 凍結した条件は [all] には残る ── 保存済みのルールを読むために要るため。
     */
    fun selectable(): List<ConditionType> = types.values.filter { it.available }

    /**
     * 仲間ごとに束ねた、いま選べる条件。選ぶ画面はこれを並べる。
     *
     * 空の仲間は落とす ── 見出しだけあって中身が無い棚は、探すのを邪魔するだけ。
     */
    fun byGroup(): List<Pair<ConditionGroup, List<ConditionType>>> =
        ConditionGroup.ORDER.mapNotNull { group ->
            selectable().filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
        }

    /**
     * 名前・説明・例・IDから探す。
     *
     * ID も見るのは、**説明で使っている言葉を思い出せないとき**に
     * `window_budget` のような手がかりで辿れるようにするため。
     * 空の問い合わせは全部返す(絞っていない、という意味)。
     */
    fun search(query: String): List<ConditionType> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return selectable()
        return selectable().filter {
            it.displayName.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.example.lowercase().contains(q) ||
                it.id.contains(q)
        }
    }

    /** テスト用。 */
    fun clear() = types.clear()
}
