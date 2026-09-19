package com.dopachiru.desktop.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ショートカットから実行ファイル名を取り出すところ。
 *
 * バイト列を自前で読むので、**壊れたものを渡されても例外を投げないこと**が要。
 * スタートメニューには UWP のタイルや変わり種の .lnk が普通に混ざっていて、
 * 1つで落ちると一覧が丸ごと出なくなる。
 */
class ShellLinkTest {

    private fun tempLink(bytes: ByteArray): File =
        File.createTempFile("dopa-test", ".lnk").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    /**
     * 最小限の .lnk を組む。
     *
     * @param idListBytes HasLinkTargetIDList のぶん。null なら旗を立てない。
     */
    private fun link(
        path: String,
        idListBytes: Int = 8,
        linkInfoFlags: Int = 1,
        headerSize: Int = 0x4C,
    ): ByteArray {
        val out = ArrayList<Byte>()

        var flags = 0x00000002 // HasLinkInfo
        if (idListBytes > 0) flags = flags or 0x00000001

        // ---- ヘッダ(76バイト固定) ----
        out += int32(headerSize).toList()
        repeat(16) { out += 0 } // CLSID
        out += int32(flags).toList()
        while (out.size < 0x4C) out += 0

        // ---- LinkTargetIDList ----
        if (idListBytes > 0) {
            out += byteArrayOf((idListBytes and 0xFF).toByte(), 0).toList()
            repeat(idListBytes) { out += 0x41 }
        }

        // ---- LinkInfo ----
        // 見出し 28バイト(7つの 4バイト)のあとに文字列を置く
        val headerBytes = 28
        val pathBytes = path.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        out += int32(headerBytes + pathBytes.size).toList() // LinkInfoSize
        out += int32(headerBytes).toList() // LinkInfoHeaderSize
        out += int32(linkInfoFlags).toList() // LinkInfoFlags
        out += int32(0).toList() // VolumeIDOffset
        out += int32(headerBytes).toList() // LocalBasePathOffset
        out += int32(0).toList() // CommonNetworkRelativeLinkOffset
        out += int32(0).toList() // CommonPathSuffixOffset
        out += pathBytes.toList()

        return out.toByteArray()
    }

    @Test
    fun `実行ファイル名を取り出す`() {
        val file = tempLink(link("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"))
        assertEquals("chrome.exe", ShellLink.targetExeOf(file))
    }

    @Test
    fun `大文字は小文字に揃える`() {
        // ルールの突き合わせは小文字で持っている。ここで揃えないと当たらない
        val file = tempLink(link("D:\\Games\\WarThunder\\WIN64\\AcesLauncher.EXE"))
        assertEquals("aceslauncher.exe", ShellLink.targetExeOf(file))
    }

    @Test
    fun `IDList が無いものも読める`() {
        val file = tempLink(link("C:\\Windows\\notepad.exe", idListBytes = 0))
        assertEquals("notepad.exe", ShellLink.targetExeOf(file))
    }

    @Test
    fun `実行ファイルでなければ拾わない`() {
        // ヘルプやマニュアルへのショートカット。止める相手にならない
        val file = tempLink(link("C:\\Program Files\\Thing\\readme.txt"))
        assertNull(ShellLink.targetExeOf(file))
    }

    @Test
    fun `ローカルのパスを持たないものは拾わない`() {
        // ネットワーク先だけを指すもの。実行ファイル名が取れない
        val file = tempLink(link("C:\\x\\y.exe", linkInfoFlags = 0))
        assertNull(ShellLink.targetExeOf(file))
    }

    @Test
    fun `lnk でないものを渡しても落ちない`() {
        val file = tempLink("これはショートカットではありません".toByteArray())
        assertNull(ShellLink.targetExeOf(file))
    }

    @Test
    fun `空のファイルでも落ちない`() {
        assertNull(ShellLink.targetExeOf(tempLink(ByteArray(0))))
    }

    @Test
    fun `途中で切れていても落ちない`() {
        // 壊れた .lnk が1つあるだけで一覧が丸ごと消えるのが、いちばん困る
        val full = link("C:\\Program Files\\Thing\\thing.exe")
        assertNull(ShellLink.targetExeOf(tempLink(full.copyOf(full.size - 20))))
        assertNull(ShellLink.targetExeOf(tempLink(full.copyOf(0x50))))
    }

    @Test
    fun `見出しの大きさが違えば読まない`() {
        val file = tempLink(link("C:\\x\\y.exe", headerSize = 0x20))
        assertNull(ShellLink.targetExeOf(file))
    }

    @Test
    fun `無いファイルでも落ちない`() {
        assertNull(ShellLink.targetExeOf(File("そんなファイルはない.lnk")))
    }
}
