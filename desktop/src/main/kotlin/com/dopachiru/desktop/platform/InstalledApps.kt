package com.dopachiru.desktop.platform

import java.io.File

/**
 * 入っているアプリの一覧。スタートメニューのショートカットから作る。
 *
 * ## なぜスタートメニューなのか
 *
 * Windows には「入っているアプリ」の綺麗な一覧が無い。候補は3つあった。
 *
 *  - **レジストリのアンインストール一覧**: 名前は取れるが、実行ファイル名が
 *    入っていないことが多い。ルールが要るのは `chrome.exe` のほうなので使えない
 *  - **`Program Files` を掘る**: アンインストーラや更新用の小物まで拾ってしまい、
 *    一覧が実行ファイルの海になる
 *  - **スタートメニュー**: 本人が起動に使うものだけが、**本人が見ている名前で**
 *    並んでいる。欲しいのはまさにそれ
 *
 * ## 取りこぼしは埋める
 *
 * スタートメニューに出ないもの(ストア版アプリ、ポータブル版、ゲームのランチャーから
 * 起動するもの)は拾えない。なので画面では **いま動いているもの([RunningApps])と
 * 混ぜて**出し、それでも足りなければ実行ファイル名を直接打てるようにしてある。
 */
object InstalledApps {

    /** 一覧に出すもの。 */
    data class Entry(val processName: String, val label: String)

    /**
     * 掘る深さ。
     *
     * 「フォルダ > アプリ名.lnk」が普通だが、Windows の付属ツールはもう1段深い
     * ところに入っている。浅くすると取りこぼすほうが目立つので4にしてある
     * ── 探す欄を付けたので、多めに出しても困らない。
     */
    private const val MAX_DEPTH = 4

    /** 1回の走査で見る .lnk の上限。壊れた環境で延々と歩き続けないための蓋。 */
    private const val MAX_LINKS = 2000

    /**
     * そもそもアプリではないもの。
     *
     * **絞りすぎない。** 探す欄があるので一覧が長くても困らないが、
     * 消してしまうと「入れてあるのに出てこない」になって直しようがない。
     * 落とすのは、押しても止める相手にならないと言い切れるものだけ。
     */
    private val NOISE = listOf(
        "uninstall", "アンインストール",
        "readme", "release note", "documentation", "ドキュメント",
        "manual", "マニュアル", "online doc",
        "home page", "web site", "ホームページ",
    )

    @Volatile
    private var cache: List<Entry>? = null

    /**
     * 一覧。**同じ実行ファイルは1つにまとめる**(同じ exe を指す .lnk が複数あるのは普通)。
     *
     * 走査はファイルを何百個も開くので、一度作ったら覚えておく。
     * 入れ直した直後に出ないことがあるが、[refresh] で作り直せる。
     */
    fun all(): List<Entry> = cache ?: scan().also { cache = it }

    /** 覚えたものを捨てて、次に読むときに作り直させる。 */
    fun refresh(): List<Entry> = scan().also { cache = it }

    private fun scan(): List<Entry> {
        val roots = listOfNotNull(
            System.getenv("ProgramData")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
            System.getenv("APPDATA")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
        ).filter { it.isDirectory }

        val found = LinkedHashMap<String, String>()
        var seen = 0

        for (root in roots) {
            for (link in walk(root, MAX_DEPTH)) {
                if (seen++ > MAX_LINKS) break
                val name = link.nameWithoutExtension
                if (NOISE.any { name.contains(it, ignoreCase = true) }) continue
                val exe = ShellLink.targetExeOf(link) ?: continue
                // 同じ exe を指す .lnk は普通に複数ある。**短い名前のほうを残す** ──
                // 「Python 3.14」と「PyDoc (Python 3.14)」が同じ python.exe を指すとき、
                // 探しているのは前者。先勝ちにすると並び順で決まってしまう
                val previous = found[exe]
                if (previous == null || name.length < previous.length) found[exe] = name
            }
        }

        return found.map { (exe, label) -> Entry(exe, label) }.sortedBy { it.label.lowercase() }
    }

    /** 深さを切って歩く。`walkTopDown` だと壊れた連結で戻ってこないことがある。 */
    private fun walk(dir: File, depth: Int): List<File> {
        if (depth <= 0) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        val here = children.filter { it.isFile && it.name.endsWith(".lnk", ignoreCase = true) }
        val below = children.filter { it.isDirectory }.flatMap { walk(it, depth - 1) }
        return here + below
    }
}
