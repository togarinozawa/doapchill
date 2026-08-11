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

    /** テスト用。 */
    fun clear() = types.clear()
}
