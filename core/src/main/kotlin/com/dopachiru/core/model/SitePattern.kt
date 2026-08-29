package com.dopachiru.core.model

/**
 * URL の当たり判定。
 *
 * ブラウザは1つのアプリなので、パッケージ名では「YouTube のショートだけ止める」が
 * 書けない。止めたいのはアプリではなく**ページ**なので、対象の指定に URL を足す。
 *
 * ## 書き方
 *
 * `ホスト` か `ホスト/パスの先頭` だけ。正規表現もワイルドカードも受け付けない。
 *
 * | 書いたもの | 当たる | 当たらない |
 * |---|---|---|
 * | `youtube.com` | youtube.com のすべて | それ以外 |
 * | `youtube.com/shorts` | `/shorts` と `/shorts/以下` | `/watch`, `/shortstack` |
 * | `tiktok.com` | www. や m. などの下位ドメインも | `nottiktok.com` |
 *
 * 正規表現にしていないのは、**書き間違いが「どこにも当たらないルール」になって
 * 静かに効かなくなる**ため。止めているつもりで止まっていない状態がいちばん悪い。
 *
 * ## 受け取り側の甘さ
 *
 * URL をそのまま貼っても通るように、パターン側の `https://` と先頭の `www.` は
 * 落とす。ホストとパスは小文字に揃えて比べる。
 * クエリ(`?v=...`)と断片(`#...`)は見ない ── パスまでで足りるし、
 * クエリまで見ると順番違いで当たらなくなる。
 */
object SitePattern {

    /** ブラウザ以外(`chrome://`, `file://` など)は対象外として扱う。 */
    private val WEB_SCHEMES = setOf("http", "https")

    /**
     * [url] が [pattern] に当たるか。
     *
     * どちらかが空、あるいは URL が web のものでなければ false。
     */
    fun matches(pattern: String, url: String): Boolean {
        val pat = parse(pattern, isPattern = true) ?: return false
        val target = parse(url, isPattern = false) ?: return false

        val hostOk = target.host == pat.host || target.host.endsWith("." + pat.host)
        if (!hostOk) return false

        // パス無しのパターンはそのホスト全体
        if (pat.path.isEmpty() || pat.path == "/") return true

        val p = pat.path.trimEnd('/')
        return target.path == p || target.path.startsWith("$p/")
    }

    /** [patterns] のどれかに当たるか。 */
    fun matchesAny(patterns: Set<String>, url: String?): Boolean {
        if (url.isNullOrBlank() || patterns.isEmpty()) return false
        return patterns.any { matches(it, url) }
    }

    /** 人に見せる形。`youtube.com/shorts` のように正規化して返す。 */
    fun normalize(pattern: String): String {
        val p = parse(pattern, isPattern = true) ?: return pattern.trim()
        val path = p.path.trimEnd('/')
        return if (path.isEmpty() || path == "/") p.host else p.host + path
    }

    /** 書き方として通るか。設定画面で保存前に弾くために使う。 */
    fun isValid(pattern: String): Boolean {
        val p = parse(pattern, isPattern = true) ?: return false
        // ホストにドットが1つも無いものは打ち間違いとみなす("youtube" など)
        return p.host.contains('.')
    }

    private data class Parts(val host: String, val path: String)

    private fun parse(raw: String, isPattern: Boolean): Parts? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) {
            val scheme = s.substring(0, schemeEnd).lowercase()
            if (scheme !in WEB_SCHEMES) return null
            s = s.substring(schemeEnd + 3)
        } else if (!isPattern && s.contains(':') && !s.contains('/')) {
            // "chrome://newtab" のようにスキームだけで終わるもの
            return null
        }

        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        var authority = if (cut >= 0) s.substring(0, cut) else s
        var path = if (cut >= 0 && s[cut] == '/') s.substring(cut) else ""

        // user:pass@host を捨てる
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        // ポートを捨てる(IPv6 は対象外)
        val colon = authority.lastIndexOf(':')
        if (colon >= 0 && authority.indexOf('[') < 0) authority = authority.substring(0, colon)

        var host = authority.lowercase().trimEnd('.')
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        if (host.isEmpty()) return null

        // パスからクエリと断片を落とす
        val q = path.indexOfFirst { it == '?' || it == '#' }
        if (q >= 0) path = path.substring(0, q)

        return Parts(host, path.lowercase())
    }
}
