package com.dopachiru.core.model

import com.dopachiru.core.condition.ConditionRegistry

/**
 * 条件の木を編集するための道具。
 *
 * 木そのもの([ConditionNode])は不変なので、書き換えは「新しい木を作って返す」形になる。
 * 画面側は編集用の別モデルを持たず、保存する木をそのまま触る ──
 * 表示と保存内容がずれないのが狙い。
 *
 * ## 位置の表し方
 * [NodePath] は根からの添字の並び。`[]` が根、`[1, 0]` は「根の2番目の子の1番目の子」。
 *
 * ## 否定の扱い
 * [ConditionNode.Not] は木の上では1段の入れ子だが、画面では「〜でないとき」という
 * **札**として見せたい。そこで添字は Not を透かして数える。
 * `Not(AllOf([a, b]))` の `[0]` は `a` を指す。
 */
typealias NodePath = List<Int>

object ConditionTree {

    /** 空の木。条件なし = 常に成立。 */
    val EMPTY: ConditionNode = ConditionNode.AllOf()

    /** 否定を剥がして、中身と「否定されているか」を返す。二重否定は打ち消す。 */
    fun stripNot(node: ConditionNode): Pair<ConditionNode, Boolean> {
        var current = node
        var negated = false
        while (current is ConditionNode.Not) {
            current = current.child
            negated = !negated
        }
        return current to negated
    }

    /** かたまりなら子の一覧、葉なら null。否定は透かす。 */
    fun childrenOf(node: ConditionNode): List<ConditionNode>? =
        when (val inner = stripNot(node).first) {
            is ConditionNode.AllOf -> inner.children
            is ConditionNode.AnyOf -> inner.children
            else -> null
        }

    /** かたまりか。 */
    fun isGroup(node: ConditionNode): Boolean = childrenOf(node) != null

    /** かたまりの繋ぎ方。AND なら true。葉なら true を返す(呼び出し側では使わない)。 */
    fun isAll(node: ConditionNode): Boolean = stripNot(node).first !is ConditionNode.AnyOf

    fun isNegated(node: ConditionNode): Boolean = stripNot(node).second

    // ------------------------------------------------------------------

    fun nodeAt(root: ConditionNode, path: NodePath): ConditionNode? {
        var current = root
        for (index in path) {
            val children = childrenOf(current) ?: return null
            current = children.getOrNull(index) ?: return null
        }
        return current
    }

    /** [path] の位置を [replacement] に差し替えた木。位置が無ければ元のまま返す。 */
    fun replaceAt(root: ConditionNode, path: NodePath, replacement: ConditionNode): ConditionNode {
        if (path.isEmpty()) return replacement
        val children = childrenOf(root) ?: return root
        val index = path.first()
        if (index !in children.indices) return root
        val updated = children.toMutableList()
        updated[index] = replaceAt(children[index], path.drop(1), replacement)
        return rebuild(root, updated)
    }

    /** [path] の位置を消した木。根は消せないので、空のかたまりになる。 */
    fun removeAt(root: ConditionNode, path: NodePath): ConditionNode {
        if (path.isEmpty()) return EMPTY
        val parentPath = path.dropLast(1)
        val index = path.last()
        val parent = nodeAt(root, parentPath) ?: return root
        val children = childrenOf(parent) ?: return root
        if (index !in children.indices) return root
        return replaceAt(
            root,
            parentPath,
            rebuild(parent, children.filterIndexed { i, _ -> i != index }),
        )
    }

    /** [path] のかたまりの末尾に [child] を足す。葉を指していたら何もしない。 */
    fun addChild(root: ConditionNode, path: NodePath, child: ConditionNode): ConditionNode {
        val parent = nodeAt(root, path) ?: return root
        val children = childrenOf(parent) ?: return root
        return replaceAt(root, path, rebuild(parent, children + child))
    }

    /** [path] の否定を切り替える。 */
    fun setNegated(root: ConditionNode, path: NodePath, negated: Boolean): ConditionNode {
        val node = nodeAt(root, path) ?: return root
        val (inner, currentlyNegated) = stripNot(node)
        if (currentlyNegated == negated) return root
        return replaceAt(root, path, if (negated) ConditionNode.Not(inner) else inner)
    }

    /** [path] のかたまりを AND / OR で切り替える。 */
    fun setAll(root: ConditionNode, path: NodePath, all: Boolean): ConditionNode {
        val node = nodeAt(root, path) ?: return root
        val (inner, negated) = stripNot(node)
        val children = childrenOf(inner) ?: return root
        val swapped =
            if (all) ConditionNode.AllOf(children) else ConditionNode.AnyOf(children)
        return replaceAt(root, path, if (negated) ConditionNode.Not(swapped) else swapped)
    }

    /** [path] の葉のパラメータを差し替える。 */
    fun setParams(
        root: ConditionNode,
        path: NodePath,
        params: com.dopachiru.core.param.Params,
    ): ConditionNode {
        val node = nodeAt(root, path) ?: return root
        val (inner, negated) = stripNot(node)
        if (inner !is ConditionNode.Leaf) return root
        val updated = inner.copy(params = params)
        return replaceAt(root, path, if (negated) ConditionNode.Not(updated) else updated)
    }

    /**
     * 子の一覧だけ差し替えた同じ形のかたまりを作る。否定と AND/OR はそのまま。
     * 葉に対して呼ばれたら元のまま返す。
     */
    private fun rebuild(node: ConditionNode, children: List<ConditionNode>): ConditionNode {
        val (inner, negated) = stripNot(node)
        val rebuilt = when (inner) {
            is ConditionNode.AllOf -> ConditionNode.AllOf(children)
            is ConditionNode.AnyOf -> ConditionNode.AnyOf(children)
            else -> return node
        }
        return if (negated) ConditionNode.Not(rebuilt) else rebuilt
    }

    // ------------------------------------------------------------------

    /** 葉の数。「条件なし」の判定に使う。 */
    fun leafCount(node: ConditionNode): Int = when (val inner = stripNot(node).first) {
        is ConditionNode.Leaf -> 1
        is ConditionNode.AllOf -> inner.children.sumOf { leafCount(it) }
        is ConditionNode.AnyOf -> inner.children.sumOf { leafCount(it) }
        else -> 0
    }

    /** 使われている条件IDを全部集める。カレンダーを読む必要があるか等の判定に使う。 */
    fun leafTypeIds(node: ConditionNode): Set<String> = when (val inner = stripNot(node).first) {
        is ConditionNode.Leaf -> setOf(inner.typeId)
        is ConditionNode.AllOf -> inner.children.flatMapTo(HashSet()) { leafTypeIds(it) }
        is ConditionNode.AnyOf -> inner.children.flatMapTo(HashSet()) { leafTypeIds(it) }
        else -> emptySet()
    }

    /**
     * 木を1行の日本語にする。一覧に出して、開かずに中身が分かるようにするため。
     *
     * 例: `夜のあいだ かつ (連続して使った時間 または 開いた回数)`
     */
    fun describe(node: ConditionNode, depth: Int = 0): String {
        val (inner, negated) = stripNot(node)
        val body = when (inner) {
            is ConditionNode.Leaf ->
                ConditionRegistry[inner.typeId]?.summarize(inner.params) ?: inner.typeId

            is ConditionNode.AllOf -> joinChildren(inner.children, "かつ", depth)
            is ConditionNode.AnyOf -> joinChildren(inner.children, "または", depth)
            else -> ""
        }
        return if (negated) "$body ではない" else body
    }

    private fun joinChildren(
        children: List<ConditionNode>,
        separator: String,
        depth: Int,
    ): String {
        if (children.isEmpty()) return if (depth == 0) "条件なし" else "空"
        val joined = children.joinToString(" $separator ") { describe(it, depth + 1) }
        return if (depth == 0 || children.size == 1) joined else "($joined)"
    }
}
