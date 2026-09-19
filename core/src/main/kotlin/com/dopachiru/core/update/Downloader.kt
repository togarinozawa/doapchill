package com.dopachiru.core.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新のファイルを落とす。
 *
 * [com.dopachiru.core.sync.SyncApi] と同じく `HttpURLConnection` で書いてあります
 * ── Android と Windows の両方で動く唯一の選択肢だからです。
 *
 * **呼ぶ側が別スレッドに逃がしてください。** ここは素直に待ちます。
 *
 * ## 途中で切れたものを残さない
 *
 * 落とし終える前に切れたファイルをインストーラに渡すと、「壊れています」と
 * 言われるだけで、原因が回線だと分かりません。**書くのは別名にして、
 * 大きさを確かめてから本来の名前に付け替えます。** 途中で切れたぶんは消します。
 */
object Downloader {

    sealed interface Result {
        data class Ok(val file: File) : Result

        data class Failed(val message: String) : Result
    }

    /** GitHub の配布先は別のホストに飛ばされる。追いかけないと何も落ちてこない。 */
    private const val MAX_REDIRECTS = 5

    /**
     * @param expectedBytes 分かっていれば渡す。落とし終えたあとに突き合わせます。
     * @param onProgress 0..100。分からないときは呼ばれません。
     */
    fun download(
        url: String,
        dest: File,
        expectedBytes: Long = 0L,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        if (!url.startsWith("https://")) return Result.Failed("落とし先の住所が https ではありません")

        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".part")
        runCatching { temp.delete() }

        var current = url
        var connection: HttpURLConnection? = null
        try {
            for (hop in 0..MAX_REDIRECTS) {
                connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    // https → https は自動で追いますが、飛び先のホストが変わると
                    // 実装によっては止まるので、自分で追えるようにしてあります
                    instanceFollowRedirects = true
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location")
                        ?: return Result.Failed("飛び先が示されていません")
                    connection.disconnect()
                    current = URL(URL(current), next).toString()
                    if (!current.startsWith("https://")) {
                        return Result.Failed("飛び先が https ではありません")
                    }
                    continue
                }
                if (code !in 200..299) return Result.Failed("落とせませんでした($code)")
                break
            }

            val live = connection ?: return Result.Failed("つながりません")
            val total = if (expectedBytes > 0L) expectedBytes else live.contentLengthLong
            var written = 0L
            var lastPercent = -1

            live.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) {
                            temp.delete()
                            return Result.Failed("やめました")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0L) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }

            if (expectedBytes > 0L && written != expectedBytes) {
                temp.delete()
                return Result.Failed("途中で切れました(" + written + " / " + expectedBytes + " バイト)")
            }

            runCatching { dest.delete() }
            if (!temp.renameTo(dest)) {
                temp.delete()
                return Result.Failed("置き場所に書けませんでした")
            }
            return Result.Ok(dest)
        } catch (e: IOException) {
            temp.delete()
            return Result.Failed("つながりません" + (e.message?.let { ": " + it.take(80) } ?: ""))
        } catch (e: Exception) {
            temp.delete()
            return Result.Failed((e.message ?: e::class.simpleName.orEmpty()).take(120))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
