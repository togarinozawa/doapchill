package com.dopachiru.core.action

/** 使えるアクションの一覧。 */
object ActionRegistry {
    private val types = LinkedHashMap<String, ActionType>()

    fun register(vararg newTypes: ActionType) {
        for (type in newTypes) {
            val previous = types.put(type.id, type)
            check(previous == null) { "アクションIDが重複している: ${type.id}" }
        }
    }

    operator fun get(id: String): ActionType? = types[id]

    fun requireType(id: String): ActionType =
        types[id] ?: error("未登録のアクションID: $id")

    fun all(): List<ActionType> = types.values.toList()

    /** テスト用。 */
    fun clear() = types.clear()
}
