package com.dopachiru.desktop.platform

import java.io.File

/**
 * ショートカット(`.lnk`)から、指している実行ファイルの名前を取り出す。
 *
 * ## なぜ自前で読むのか
 *
 * 素直にやるなら COM の `IShellLink` だが、JNA から COM を叩くのは行数のわりに
 * 壊れやすい。PowerShell を起動する手もあるが、1回あたり1〜2秒かかるうえ、
 * 実行ポリシーの影響を受ける ── **アプリ一覧を出すだけのことで、外の都合に
 * 依存したくない。**
 *
 * 幸い Shell Link の形式は公開されていて([MS-SHLLINK])、要るのは
 * `LinkInfo` の中の `LocalBasePath` 1つだけ。そこだけ読む。
 *
 * ## 読めなかったら諦める
 *
 * 形式に合わないもの、ネットワーク先を指すもの、UWP アプリのタイルなどは
 * null を返して**黙って飛ばす**。一覧に出ないだけで、実行ファイル名を
 * 直接打ち込む道は残っている。ここで例外を投げると、変な .lnk が1つあるだけで
 * 一覧が丸ごと出なくなる。
 */
object ShellLink {

    /** ヘッダの大きさ。仕様で固定。 */
    private const val HEADER_SIZE = 0x4C

    private const val FLAG_HAS_LINK_TARGET_ID_LIST = 0x00000001
    private const val FLAG_HAS_LINK_INFO = 0x00000002

    /** LinkInfo が「ボリューム + ローカルのパス」を持っているか。 */
    private const val LINK_INFO_HAS_LOCAL_BASE_PATH = 0x00000001

    /**
     * @return 指している実行ファイルの名前(`chrome.exe`)。読めなければ null。
     */
    fun targetExeOf(file: File): String? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size < HEADER_SIZE) return null
        // ヘッダの先頭は必ず 0x4C。違うなら .lnk ではない
        if (int32(bytes, 0) != HEADER_SIZE) return null

        val flags = int32(bytes, 20)
        var offset = HEADER_SIZE

        // 目当ての手前に可変長のものが1つある。大きさを読んで飛ばす
        if (flags and FLAG_HAS_LINK_TARGET_ID_LIST != 0) {
            if (offset + 2 > bytes.size) return null
            offset += 2 + int16(bytes, offset)
        }

        if (flags and FLAG_HAS_LINK_INFO == 0) return null
        if (offset + 20 > bytes.size) return null

        val linkInfoStart = offset
        val linkInfoFlags = int32(bytes, linkInfoStart + 8)
        if (linkInfoFlags and LINK_INFO_HAS_LOCAL_BASE_PATH == 0) return null

        val pathOffset = int32(bytes, linkInfoStart + 16)
        val pathStart = linkInfoStart + pathOffset
        if (pathStart !in bytes.indices) return null

        // ヌル止めの ANSI 文字列。名前に非 ASCII が混じっていても、
        // 欲しいのは末尾の `〜.exe` なので既定の文字集合で足りる
        val end = (pathStart until bytes.size).firstOrNull { bytes[it] == 0.toByte() } ?: bytes.size
        val path = String(bytes, pathStart, end - pathStart)

        path.substringAfterLast('\\')
            .substringAfterLast('/')
            .takeIf { it.endsWith(".exe", ignoreCase = true) }
            ?.lowercase()
    }.getOrNull()

    private fun int16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun int32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
