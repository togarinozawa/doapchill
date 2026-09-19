package com.dopachiru.core.update

/**
 * 版の比べかた。
 *
 * ## なぜ文字として比べないのか
 *
 * `"1.2.10" < "1.2.9"` は文字として比べれば真です。放っておくと
 * **10 個目の修正を出した瞬間に「最新です」と言い出す**。
 * 桁上がりは必ず起きるので、最初から数として比べます。
 *
 * ## 分からないときは「新しくない」と答える
 *
 * 版が読めないのは、たいてい**こちらが壊れている**とき(入っている
 * パッケージから版が読めない、サーバーが空を返した)。そこで
 * 「新しい版があります」と出すと、壊れているときにかぎって
 * 更新を勧めることになります。黙るほうが安全側です。
 */
object Versions {

    /** `1.2.10` と `1.2.9` を数として比べる。左が新しければ正。 */
    fun compare(a: String, b: String): Int {
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }

    /**
     * [candidate] は [current] より新しいか。
     *
     * どちらかが読めなければ偽。上の「分からないときは黙る」がここ。
     */
    fun isNewer(current: String, candidate: String): Boolean {
        if (parts(current).isEmpty() || parts(candidate).isEmpty()) return false
        return compare(candidate, current) > 0
    }

    /**
     * `0.24.0` を `[0, 24, 0]` に。
     *
     * `1.2.0-rc1` のような尻尾は切って数だけ見ます ── 尻尾の順序を
     * 決め始めると規則が増える一方で、個人用の配布には要りません。
     */
    private fun parts(version: String): List<Int> {
        val head = version.trim().takeWhile { it.isDigit() || it == '.' }.trim('.')
        if (head.isEmpty()) return emptyList()
        return head.split('.').mapNotNull { it.toIntOrNull() }
    }
}
